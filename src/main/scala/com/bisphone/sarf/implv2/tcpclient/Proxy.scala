package com.bisphone.sarf.implv2.tcpclient

import scala.collection.mutable

import akka.actor.{Actor, ActorRef, Props, Stash, Terminated}
import com.bisphone.launcher.Module
import com.bisphone.sarf.{FrameWriter, TrackedFrame, UntrackedFrame}

object Proxy {

    case class NewConnection(id: Int, name: String, desc: String, ref: ActorRef)
    case class Send[U <: UntrackedFrame[_]](frame: U, requestTime: Long)
    case class Recieved[T <: TrackedFrame](frame: T)

    case class HealthCheck(desc: String)

    def props[T <: TrackedFrame, U <: UntrackedFrame[T]](
        name: String,
        director: ActorRef,
        writer: FrameWriter[T, U]
    ): Props = Props { new Proxy(name, director, writer) }

}

class Proxy[T <: TrackedFrame, U <: UntrackedFrame[T]](
    val name: String,
    director: ActorRef,
    writer: FrameWriter[T,U]
) extends Actor with Module {

    val logger = loadLogger

    val conns = mutable.HashMap.empty[Int, ConnectionContext]

    val tracker = new Tracker(s"${name}.tracker")

    val balancer = new RoundRobinConnectionBalancer(s"${name}.balancer")

    val queued = mutable.Queue.empty[RequestContext]

    val unit = ()

    import Proxy._

    override def receive: Receive = {

        case Proxy.HealthCheck(desc) =>
            logger info s"HealthCheck, Desc: ${desc}"
            sender ! unit

        case Proxy.Send(frame: U, time) =>

            balancer.pickOne match {

                case Some(conn) =>
                    val ctx = tracker track (sender, conn)
                    val tracked = writer writeFrame (frame, ctx.trackingKey)
                    conn.ref ! Connection.Send(tracked)
                    logger trace s"Send, TrackingKey: ${ctx.trackingKey}, TypeKey: ${frame.dispatchKey.typeKey}, Caller: ${ctx.caller}, Connection: ${conn}"

                case None =>
                    logger warn s"No Available Connection !!!"
                    // @todo stash
            }


        case Connection.Recieved(frame) =>
            tracker.resolve(frame.trackingKey) match {
                case Some(ctx) =>
                    logger trace s"Received, ${ctx}, ${frame}"
                    ctx.caller ! Proxy.Recieved(frame)
                case None =>
                    logger error s"Unrequested Response, TrackingKey: ${frame.trackingKey}"
            }

        case Proxy.NewConnection(id, name, desc, ref) =>
            logger info s"NewConnection, Name: ${name}, Id: ${id}, Desc: ${desc}"
            val conn = ConnectionContext(
                name ,desc, id, ref,
                System.currentTimeMillis,
                0, 0, 0
            )
            context watch ref
            balancer add conn

        case Terminated(ref) =>
            (balancer remove ref) match {
                case Some(ctx) =>
                    logger info s"Lost Connection, ID: ${ctx.id}, Name: ${ctx.name}, Remained: ${balancer.count}"
                    logger debug s"Lost Connection, ${ctx}"
                case None =>
                    logger warn s"Lossing Connection, UNREGISTERD"
            }

    }

}
