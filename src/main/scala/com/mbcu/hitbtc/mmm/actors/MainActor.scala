package com.mbcu.hitbtc.mmm.actors

import akka.actor.{Actor, ActorRef, Cancellable, Props, Terminated}
import akka.pattern.ask
import akka.util.Timeout

import scala.concurrent.duration._
import akka.dispatch.ExecutionContexts._
import akka.dispatch.MonitorableThreadFactory
import com.amazonaws.services.simpleemail.model.SendEmailResult
import com.mbcu.hitbtc.mmm.actors.ParserActor._
import com.mbcu.hitbtc.mmm.actors.SesActor.{MailSent, SendError}
import com.mbcu.hitbtc.mmm.actors.StateActor.SendNewOrder
import com.mbcu.hitbtc.mmm.actors.WsActor._
import com.mbcu.hitbtc.mmm.models.internal.Config
import com.mbcu.hitbtc.mmm.models.request.{Login, NewOrder, SubscribeReports}
import com.mbcu.hitbtc.mmm.models.response.{Order, RPCError}
import com.mbcu.hitbtc.mmm.utils.{MyLogging, MyLoggingSingle}
import com.sun.xml.internal.ws.api.Cancelable
import play.api.libs.json.Json

import scala.concurrent.{Await, ExecutionContext, ExecutionContextExecutor, Future}
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}

object MainActor {

  def props(configPath : String): Props = Props(new MainActor(configPath))

  case class ConfigReady(config : Try[Config])

  case class Shutdown(code :Int)

  case class HandleRPCError(er : RPCError, id : Option[String], code : Option[Int] = None)

  case class HandleError(msg :String, code : Option[Int] = None)

}

class MainActor(configPath : String) extends Actor with MyLogging {
  import com.mbcu.hitbtc.mmm.actors.MainActor._
  val ENDPOINT = "wss://api.hitbtc.com/api/2/ws"
  private var config: Option[Config] = None
  private var ws: Option[ActorRef] = None
  private var parser: Option[ActorRef] = None
  private var cancellable : Option[Cancellable] = None
  private var state : Option[ActorRef] = None
  private var ses : Option[ActorRef] = None
  implicit val ec: ExecutionContextExecutor = global

  override def receive: Receive = {

    case "start" =>
      val fileActor = context.actorOf(Props(new FileActor(configPath)))
      fileActor ! "start"

    case ConfigReady(tcfg) =>
      tcfg match {
        case Failure(f) => println(
          s"""Config error
            |$f
          """.stripMargin)
          System.exit(-1)
        case Success(cfg) =>
          config = Some(cfg)
          ses = Some(context.actorOf(Props(new SesActor(cfg.env.sesKey, cfg.env.sesSecret, cfg.env.emails)), name = "ses"))
          ses foreach (_ ! "start")
          state = Some(context.actorOf(Props(new StateActor(cfg)), name = "state"))
          state foreach (_ ! "start")
          ws = Some(context.actorOf(Props(new WsActor(ENDPOINT)), name = "ws"))
          val scheduleActor = context.actorOf(Props(classOf[ScheduleActor]))
          cancellable =Some(
            context.system.scheduler.schedule(
              10 second,
              (if (cfg.env.logSeconds < 5) 5 else cfg.env.logSeconds) second,
              scheduleActor,
              "log orderbooks"))
          ws.foreach(_ ! "start")
      }


    case s : String if s == "log orderbooks" => state foreach (_ forward s)

    case WsConnected =>
      info(s"Connected to $ENDPOINT")
      parser = Some(context.actorOf(Props(new ParserActor(config))))
      self ! "login"

    case WsDisconnected => info(s"Disconnected")

    case "login" => config.foreach(c => ws.foreach(_ ! SendJs(Json.toJson(Login.from(c)))))

    case LoginSuccess => ws.foreach(_ ! SendJs(SubscribeReports.toJsValue()))

    case SubsribeReportsSuccess => info("Subscribe Reports success")

    case activeOrders : ActiveOrders => state foreach (_ forward activeOrders)

    case OrderNew(order) => state foreach (_ ! OrderNew(order))

    case OrderCancelled(order) => state foreach(_! OrderCancelled(order))

    case OrderPartiallyFilled(order) => state foreach(_ ! OrderPartiallyFilled(order))

    case OrderFilled(order) => state foreach(_ ! OrderFilled(order))

    case OrderExpired(order) => state foreach(_ ! OrderExpired(order))

    case OrderSuspended(order) => error(s"Suspended id : ${order.clientOrderId} symbol:${order.symbol} side:${order.side}")

    case SendNewOrder(newOrder, as) =>
      info(
        s"""Sending new order as $as
           |$newOrder""".stripMargin)
      ws foreach (_ ! SendJs(Json.toJson(newOrder)))

    case ErrorNonAffecting(er, id) => self ! HandleRPCError(er, id)

    case ErrorNotEnoughFund(er, id) =>
      self ! HandleRPCError(er, id)
      state foreach(_ ! ErrorNotEnoughFund(er, id))

    case ErrorOrderTooSmall(er, id) =>
      self ! HandleRPCError(er, id)
      state foreach(_ ! ErrorOrderTooSmall(er, id))

    case ErrorCancelGhost(er, id) =>
      self ! HandleRPCError(er, id)
      state foreach(_ ! ErrorCancelGhost(er, id))

    case ErrorSymbol(er, id) => self ! HandleRPCError(er, id, Some(-1))

    case ErrorAuthFailed(er, id) => self ! HandleRPCError(er, id, Some(-1))

    case ErrorServer(er, id) => self ! HandleRPCError(er, id, Some(1))

    case WSError(er, code) => self ! HandleError(er , code)

    case wsGotText : WsGotText =>
      parser match {
        case Some(parserActor) => parserActor ! wsGotText
        case _ => warn("MainActor#wsGotText : _")
      }

    case HandleRPCError(er, id, code ) =>
      val s = s"""${er.toString}
         |id $id""".stripMargin
      handleError(s, code)

    case HandleError(msg, code) => handleError(msg, code)

    case MailSent(t, shutdownCode) =>
      t match {
        case Success(_) => info("Email Sent")
        case Failure(c) => info(
          s"""Failed sending email
            |${c.getMessage}
          """.stripMargin)
      }
      shutdownCode match {
        case Some(code) => self ! Shutdown(code)
        case _ =>
      }

    case Shutdown(code) =>
      info(s"Stopping application, code $code")
      implicit val executionContext: ExecutionContext = context.system.dispatcher
      context.system.scheduler.scheduleOnce(Duration.Zero)(System.exit(code))
  }

  def handleError(s: String, code : Option[Int]) : Unit = {
    error(s)
    ses foreach(_ ! SendError(s, code))
  }


}
