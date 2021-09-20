package com.lnvortex.server

import akka.actor._
import com.lnvortex.core.RoundStatus._
import com.lnvortex.core._
import com.lnvortex.server.config.VortexCoordinatorAppConfig
import com.lnvortex.server.models._
import grizzled.slf4j.Logging
import org.bitcoins.core.currency.{CurrencyUnit, Satoshis}
import org.bitcoins.core.number._
import org.bitcoins.core.protocol.BitcoinAddress
import org.bitcoins.core.protocol.script.{EmptyScriptSignature, ScriptPubKey}
import org.bitcoins.core.protocol.transaction._
import org.bitcoins.core.psbt.PSBT
import org.bitcoins.core.script.ScriptType.WITNESS_V0_KEYHASH
import org.bitcoins.core.util._
import org.bitcoins.core.wallet.builder._
import org.bitcoins.core.wallet.fee.SatoshisPerVirtualByte
import org.bitcoins.crypto._
import org.bitcoins.feeprovider.MempoolSpaceProvider
import org.bitcoins.feeprovider.MempoolSpaceTarget.FastestFeeTarget
import org.bitcoins.rpc.client.common.BitcoindRpcClient

import java.net.InetSocketAddress
import java.time.Instant
import scala.collection.mutable
import scala.concurrent._
import scala.concurrent.duration.DurationInt
import scala.util.{Failure, Success, Try}

case class VortexCoordinator(bitcoind: BitcoindRpcClient)(implicit
    system: ActorSystem,
    val config: VortexCoordinatorAppConfig)
    extends StartStopAsync[Unit]
    with Logging {
  import system.dispatcher

  final val version = UInt16.zero

  private[server] val bannedUtxoDAO = BannedUtxoDAO()
  private[server] val aliceDAO = AliceDAO()
  private[server] val inputsDAO = RegisteredInputDAO()
  private[server] val outputsDAO = RegisteredOutputDAO()
  private[server] val roundDAO = RoundDAO()

  private[this] val km = new CoordinatorKeyManager()

  private val feeProvider: MempoolSpaceProvider =
    MempoolSpaceProvider(FastestFeeTarget, config.network, None)

  private var feeRate: SatoshisPerVirtualByte =
    SatoshisPerVirtualByte.fromLong(10)

  private var currentRoundId: DoubleSha256Digest =
    CryptoUtil.doubleSHA256(ECPrivateKey.freshPrivateKey.bytes)

  def inputFee: CurrencyUnit = feeRate * 149 // p2wpkh input size
  def outputFee: CurrencyUnit = feeRate * 43 // p2wsh output size

  private var beginInputRegistrationCancellable: Option[Cancellable] = None

  // On startup consider a round just happened so
  // next round occurs at the interval time
  private var lastRoundTime: Long = TimeUtil.currentEpochSecond

  private def nextRoundTime: Long = {
    lastRoundTime + config.interval.toSeconds
  }

  def mixDetails: MixDetails =
    MixDetails(
      version = version,
      roundId = currentRoundId,
      amount = config.mixAmount,
      mixFee = config.mixFee,
      inputFee = inputFee,
      outputFee = outputFee,
      publicKey = km.publicKey,
      time = UInt64(nextRoundTime)
    )

  private[server] val connectionHandlerMap: mutable.Map[
    Sha256Digest,
    ActorRef] = mutable.Map.empty

  private[server] var outputsRegisteredP: Promise[Unit] = Promise[Unit]()

  private[server] val signedPMap: mutable.Map[Sha256Digest, Promise[PSBT]] =
    mutable.Map.empty

  def newRound(): Future[RoundDb] = {
    val feeRateF = updateFeeRate()

    outputsRegisteredP = Promise[Unit]()
    lastRoundTime = TimeUtil.currentEpochSecond
    connectionHandlerMap.clear()
    signedPMap.clear()
    // generate new round id
    currentRoundId = CryptoUtil.doubleSHA256(ECPrivateKey.freshPrivateKey.bytes)

    for {
      feeRate <- feeRateF
      roundDb = RoundDbs.newRound(
        roundId = currentRoundId,
        roundTime = Instant.ofEpochSecond(nextRoundTime),
        feeRate = feeRate,
        mixFee = config.mixFee,
        inputFee = inputFee,
        outputFee = outputFee,
        amount = config.mixAmount
      )
      created <- roundDAO.create(roundDb)
    } yield {
      beginInputRegistrationCancellable = Some(
        system.scheduler.scheduleOnce(config.interval) {
          beginInputRegistration()
          ()
        })
      outputsRegisteredP.future.map(_ => beginOutputRegistration())
      created
    }
  }

  private[server] def beginInputRegistration(): Future[Unit] = {
    beginInputRegistrationCancellable.foreach(_.cancel())
    beginInputRegistrationCancellable = None
    for {
      roundDb <- roundDAO.read(currentRoundId).map(_.get)
      updated = roundDb.copy(status = RegisterAlices)
      _ <- roundDAO.update(updated)
    } yield ()
  }

  private[server] def beginOutputRegistration(): Future[Unit] = {
    for {
      roundDb <- roundDAO.read(currentRoundId).map(_.get)
      updated = roundDb.copy(status = RegisterOutputs)
      _ <- roundDAO.update(updated)
    } yield ()
  }

  def getNonce(
      peerId: Sha256Digest,
      connectionHandler: ActorRef,
      askNonce: AskNonce): Future[NonceMessage] = {
    require(askNonce.roundId == currentRoundId)
    aliceDAO.read(peerId).flatMap {
      case Some(alice) =>
        Future.successful(NonceMessage(alice.nonce))
      case None =>
        val (nonce, path) = km.nextNonce()

        val aliceDb = AliceDbs.newAlice(peerId, currentRoundId, path, nonce)

        connectionHandlerMap.put(peerId, connectionHandler)

        aliceDAO.create(aliceDb).map(_ => NonceMessage(nonce))
    }
  }

  def registerAlice(
      peerId: Sha256Digest,
      registerInputs: RegisterInputs): Future[BlindedSig] = {
    require(registerInputs.inputs.forall(
              _.output.scriptPubKey.scriptType == WITNESS_V0_KEYHASH),
            s"${peerId.hex} attempted to register non p2wpkh inputs")

    val roundDbF = roundDAO.read(currentRoundId).map {
      case None => throw new RuntimeException("No roundDb found")
      case Some(roundDb) =>
        require(roundDb.status == RegisterAlices)
        roundDb
    }

    val peerNonceF = aliceDAO.read(peerId).map(_.get.nonce)

    val verifyInputFs = registerInputs.inputs.map { inputRef: InputReference =>
      import inputRef._
      for {
        banDbOpt <- bannedUtxoDAO.read(outPoint)
        notBanned = banDbOpt match {
          case Some(banDb) =>
            TimeUtil.now.isAfter(banDb.bannedUntil)
          case None => true
        }

        txResult <- bitcoind.getRawTransaction(outPoint.txIdBE)
        txOutT = Try(txResult.vout(outPoint.vout.toInt))
        isRealInput = txOutT match {
          case Failure(_) => false
          case Success(out) =>
            TransactionOutput(out.value,
                              ScriptPubKey(out.scriptPubKey.hex)) == output
        }

        peerNonce <- peerNonceF
        validProof = InputReference.verifyInputProof(inputRef, peerNonce)
      } yield notBanned && isRealInput && validProof
    }

    val f = for {
      verifyInputs <- Future.sequence(verifyInputFs)
      roundDb <- roundDbF
    } yield (verifyInputs, roundDb)

    f.flatMap { case (verifyInputVec, roundDb) =>
      val validInputs = verifyInputVec.forall(v => v)

      val inputAmt = registerInputs.inputs.map(_.output.value).sum
      val inputFees = Satoshis(registerInputs.inputs.size) * roundDb.inputFee
      val outputFees = Satoshis(2) * roundDb.outputFee
      val onChainFees = inputFees + outputFees
      val changeAmt = inputAmt - roundDb.amount - roundDb.mixFee - onChainFees

      val validChange =
        registerInputs.changeOutput.value <= changeAmt &&
          registerInputs.changeOutput.scriptPubKey.scriptType == WITNESS_V0_KEYHASH

      if (validInputs && validChange) {
        aliceDAO.read(peerId).flatMap {
          case Some(aliceDb) =>
            val sig =
              km.createBlindSig(registerInputs.blindedOutput, aliceDb.noncePath)

            val inputDbs = registerInputs.inputs.map(
              RegisteredInputDbs.fromInputReference(_, currentRoundId, peerId))

            val updated =
              aliceDb.copy(blindedOutputOpt =
                             Some(registerInputs.blindedOutput),
                           changeOutputOpt = Some(registerInputs.changeOutput),
                           blindOutputSigOpt = Some(sig))

            for {
              _ <- aliceDAO.update(updated)
              _ <- inputsDAO.createAll(inputDbs)
              registered <- aliceDAO.numRegisteredForRound(currentRoundId)

              // check if we need to stop waiting for peers
              _ <-
                if (registered >= config.maxPeers) {
                  beginOutputRegistration()
                } else Future.unit
            } yield BlindedSig(sig)
          case None =>
            Future.failed(
              new RuntimeException(s"No alice found with ${peerId.hex}"))
        }
      } else {
        val bannedUntil = TimeUtil.now.plusSeconds(3600) // 1 hour

        val banDbs = registerInputs.inputs
          .map(_.outPoint)
          .map(
            BannedUtxoDb(_, bannedUntil, "Invalid inputs and proofs received"))

        bannedUtxoDAO
          .createAll(banDbs)
          .flatMap(_ =>
            Future.failed(
              new RuntimeException("Alice registered with invalid inputs")))
      }
    }
  }

  def verifyAndRegisterBob(bob: BobMessage): Future[Boolean] = {
    if (bob.verifySigAndOutput(km.publicKey)) {
      val db = RegisteredOutputDb(bob.output, bob.sig, currentRoundId)
      for {
        roundOpt <- roundDAO.read(currentRoundId)
        _ = roundOpt match {
          case Some(round) => require(round.status == RegisterOutputs)
          case None =>
            throw new RuntimeException(
              s"No round found for roundId ${currentRoundId.hex}")
        }
        _ <- outputsDAO.create(db)

        registeredAlices <- aliceDAO.numRegisteredForRound(currentRoundId)
        outputs <- outputsDAO.findByRoundId(currentRoundId)
        _ = if (outputs.size >= registeredAlices) {
          outputsRegisteredP.success(())
        } else ()
      } yield true
    } else {
      logger.warn(s"Received invalid signature for output ${bob.output}")
      Future.successful(false)
    }
  }

  private[server] def constructUnsignedTransaction(
      mixAddr: BitcoinAddress): Future[Transaction] = {
    val dbsF = for {
      inputDbs <- inputsDAO.findByRoundId(currentRoundId)
      outputDbs <- outputsDAO.findByRoundId(currentRoundId)
      roundDb <- roundDAO.read(currentRoundId).map(_.get)
    } yield (inputDbs, outputDbs, roundDb)

    dbsF.flatMap { case (inputDbs, outputDbs, roundDb) =>
      val txBuilder = RawTxBuilder().setFinalizer(
        FilterDustFinalizer.andThen(ShuffleFinalizer))

      // add mix outputs
      txBuilder ++= outputDbs.map(_.output)
      // add inputs & change outputs
      txBuilder ++= inputDbs.map { inputDb =>
        val input = TransactionInput(inputDb.outPoint,
                                     EmptyScriptSignature,
                                     TransactionConstants.sequence)
        (input, inputDb.output)
      }

      // add mix fee output
      val mixFee = Satoshis(inputDbs.size) * config.mixFee
      txBuilder += TransactionOutput(mixFee, mixAddr.scriptPubKey)

      val transaction = txBuilder.buildTx()

      val outPoints = transaction.inputs.map(_.previousOutput)

      val psbt = PSBT.fromUnsignedTx(transaction)

      val updatedRound =
        roundDb.copy(psbtOpt = Some(psbt), status = SigningPhase)
      val updatedInputs = inputDbs.map { db =>
        val index = outPoints.indexOf(db.outPoint)
        db.copy(indexOpt = Some(index))
      }

      inputDbs.map(_.peerId).distinct.foreach { peerId =>
        signedPMap.put(peerId, Promise[PSBT]())
      }

      for {
        _ <- inputsDAO.updateAll(updatedInputs)
        _ <- roundDAO.update(updatedRound)
      } yield transaction
    }
  }

  def registerPSBTSignature(
      peerId: Sha256Digest,
      psbt: PSBT): Future[Transaction] = {

    val dbsF = for {
      roundOpt <- roundDAO.read(currentRoundId)
      inputs <- inputsDAO.findByRoundId(currentRoundId)
    } yield (roundOpt, inputs.filter(_.indexOpt.isDefined))

    dbsF.flatMap {
      case (None, _) =>
        Future.failed(
          new RuntimeException(s"No round found with id ${currentRoundId.hex}"))
      case (Some(roundDb), inputs) =>
        require(roundDb.status == SigningPhase)
        roundDb.psbtOpt match {
          case Some(unsignedPsbt) =>
            require(inputs.size == unsignedPsbt.inputMaps.size)
            val sameTx = unsignedPsbt.transaction == psbt.transaction
            lazy val verify =
              inputs.flatMap(_.indexOpt).forall(psbt.verifyFinalizedInput)
            if (sameTx && verify) {

              val signedFs = signedPMap.values.map(_.future)

              val signedT = Try {
                val psbts = Await.result(Future.sequence(signedFs), 180.seconds)

                val head = psbts.head
                val combined = psbts.tail.foldLeft(head)(_.combinePSBT(_))

                combined.extractTransactionAndValidate
              }.flatten

              for {
                tx <- Future.fromTry(signedT)

                profit = Satoshis(signedFs.size) * roundDb.mixFee
                updatedRoundDb = roundDb.copy(status = Signed,
                                              transactionOpt = Some(tx),
                                              profitOpt = Some(profit))
                _ <- roundDAO.update(updatedRoundDb)

                _ <- newRound()
              } yield tx
            } else {
              val bannedUntil = TimeUtil.now.plusSeconds(86400) // 1 day

              val dbs = inputs
                .map(_.outPoint)
                .map(BannedUtxoDb(_, bannedUntil, "Invalid psbt signature"))

              signedPMap(peerId).failure(
                new RuntimeException("Invalid psbt signature"))

              bannedUtxoDAO
                .createAll(dbs)
                .flatMap(_ =>
                  Future.failed(
                    new IllegalArgumentException(
                      s"Received invalid signature from peer ${peerId.hex}")))
            }
          case None =>
            signedPMap(peerId).failure(
              new RuntimeException("Round in invalid state, no psbt"))
            Future.failed(
              new RuntimeException("Round in invalid state, no psbt"))
        }
    }
  }

  private def updateFeeRate(): Future[SatoshisPerVirtualByte] = {
    feeProvider.getFeeRate.map { res =>
      feeRate = res
      res
    }
  }

  // -- Server startup logic --

  private val hostAddressP: Promise[InetSocketAddress] =
    Promise[InetSocketAddress]()

  private[server] lazy val serverBindF: Future[
    (InetSocketAddress, ActorRef)] = {
    logger.info(
      s"Binding coordinator to ${config.listenAddress}, with tor hidden service: ${config.torParams.isDefined}")

    val bindF = VortexServer.bind(vortexCoordinator = this,
                                  bindAddress = config.listenAddress,
                                  torParams = config.torParams)

    bindF.map { case (addr, actor) =>
      hostAddressP.success(addr)
      (addr, actor)
    }
  }

  override def start(): Future[Unit] = {
    for {
      _ <- newRound()
      _ <- serverBindF
    } yield ()
  }

  override def stop(): Future[Unit] = {
    serverBindF.map { case (_, actorRef) =>
      system.stop(actorRef)
    }
  }

  def getHostAddress: Future[InetSocketAddress] = {
    hostAddressP.future
  }
}
