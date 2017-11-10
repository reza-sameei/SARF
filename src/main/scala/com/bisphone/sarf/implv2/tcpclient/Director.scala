package com.bisphone.sarf.implv2.tcpclient

import scala.collection.mutable
import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.reflect.ClassTag
import scala.util.Try

import akka.actor.{Actor, ActorRef, Props, Terminated}
import akka.stream.ActorMaterializer
import com.bisphone.launcher.Module
import com.bisphone.sarf.{FrameReader, FrameWriter, TrackedFrame, UntrackedFrame}
import com.bisphone.std._

object Director {

    case class Config(
        connections: Seq[Connection.Config],
        minumumAdorableConnections: Int,
        maximumTroubleTime: FiniteDuration,
        maxRetry: Int,
        retryDelay: FiniteDuration,
        initTimeout: FiniteDuration,
        requestTimeout: FiniteDuration
    )

    case class ConnectionRef(
        id: Int,
        config: Connection.Config,
        state: Connection.State,
        retryCount: Int
    )

    case class RetryRef(
        config: Connection.Config,
        retryCount: Int
    )

    def props[T <: TrackedFrame, U <: UntrackedFrame[T]](
        name: String,
        config: Director.Config,
        writer: FrameWriter[T, U],
        reader: FrameReader[T],
        fnReady: StdTry[ActorRef] => Unit,
        fnUnready: Unit => Unit
    )(
        implicit
        $tracked: ClassTag[T],
        $untracked: ClassTag[U]
    ): Props = Props { new Director(name, config, writer, reader, fnReady, fnUnready) }

}

class Director[T <: TrackedFrame, U <: UntrackedFrame[T]](
    val name: String,
    config: Director.Config,
    writer: FrameWriter[T, U],
    reader: FrameReader[T],
    fnReady: StdTry[ActorRef] => Unit,
    fnUnready: Unit => Unit
)(
    implicit
    $tracked: ClassTag[T],
    $untracked: ClassTag[U]
) extends Actor with Module {

    override protected def logger = loadLogger

    val proxyName = s"${name}.proxy"
    val proxyProps = Proxy.props(proxyName, self, writer)
    val proxy = context actorOf (proxyProps, proxyName)

    var _count = 0
    def count() = {
        _count += 1
        _count
    }

    var _established = 0

    var _calledFnReady = false

    val materializer = ActorMaterializer()

    val all = mutable.HashMap.empty[ActorRef, Director.ConnectionRef]

    def newConnection(cfg: Connection.Config, retryCount: Int) = {
        val id = count
        val props = Connection.props(self, id, cfg, writer, reader, materializer, context.dispatcher)
        val actor = context actorOf props
        context watch actor
        val state = Connection.State.Trying(id, actor, cfg, System.currentTimeMillis())
        all(actor) = Director.ConnectionRef(id, cfg, state, retryCount)
    }

    def removeConnection(ref: ActorRef) = {
        all remove ref get
    }

    def renewConnection(ref: ActorRef) = {
        val tmp = (all remove ref).get
        val retryCount = tmp.state match {
            case _: Connection.State.Trying => tmp.retryCount + 1
            case _ => 0
        }
        newConnection(tmp.config, retryCount)
    }

    def scheduleForRenewing(ref: ActorRef) = {
        val tmp = removeConnection(ref)
        val retryCount = tmp.state match {
            case _: Connection.State.Trying => tmp.retryCount + 1
            case _ => 0
        }

        context.system.scheduler.scheduleOnce(
            config.retryDelay,self,
            Director.RetryRef(tmp.config, retryCount)
        )(context.dispatcher)
    }

    def setEstablishedConnection(ref: ActorRef, state: Connection.State.Established) = {
        val newVal = all(ref).copy(retryCount = 0, state = state)
        all(ref) = newVal
        newVal
    }

    override def preStart: Unit = {
        config.connections.foreach(newConnection(_, 0))
    }

    def normal: Receive = {

        case Terminated(ref) if all(ref).state.isInstanceOf[Connection.State.Established] =>
            _established -= 1
            scheduleForRenewing(ref)

        case Terminated(ref) if all contains ref =>
            scheduleForRenewing(ref)

        case st: Connection.State.Established =>
            _established += 1
            val tmp = setEstablishedConnection(sender, st)
            proxy ! Proxy.NewConnection(
                tmp.id, tmp.config.name,
                s"${tmp.config.tcp.host}:${tmp.config.tcp.port}",
                tmp.state.ref
            )

            if (_established >= config.minumumAdorableConnections && _calledFnReady) {
                _calledFnReady = true
                fnReady(StdSuccess(proxy))
            }

        case Director.RetryRef(cfg, retryCount) =>
            newConnection(cfg, retryCount)
    }

    override def receive: Receive = normal
}