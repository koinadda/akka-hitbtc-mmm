package com.mbcu.hitbtc.mmm.actors

import akka.actor.{Actor, ActorRef, Props}
import akka.pattern.ask
import akka.util.Timeout

import scala.concurrent.duration._
import akka.dispatch.ExecutionContexts._
import com.mbcu.hitbtc.mmm.actors.ParserActor.{LoginSuccess, RPCFailed, SubsribeReportsSuccess}
import com.mbcu.hitbtc.mmm.actors.WsActor._
import com.mbcu.hitbtc.mmm.models.internal.Config
import com.mbcu.hitbtc.mmm.models.request.{Login, SubscribeReports}
import com.mbcu.hitbtc.mmm.models.response.RPCError
import play.api.libs.json.Json

object MainActor {
  def props(configPath : String): Props = Props(new MainActor(configPath))

  case class ConfigInitiated(config : Config)

}

class MainActor(configPath : String) extends Actor{
  import com.mbcu.hitbtc.mmm.actors.MainActor._
  private var config: Option[Config] = None
  private var ws: Option[ActorRef] = None
  private var parser: Option[ActorRef] = None
  implicit val ec = global

  val initDone : Boolean = false

  override def receive: Receive = {

    case "start" => {
        val fileActor = context.actorOf(Props(new FileActor(configPath)))
        fileActor ! "start"
    }

    case ConfigInitiated(cfg) => {
      config = Some(cfg)
      ws = Some(context.actorOf(Props(new WsActor("wss://api.hitbtc.com/api/2/ws"))))
      ws.map(_ ! "start")
    }

    case WsConnected => {
      parser = Some(context.actorOf(Props(new ParserActor(config))))
      self ! "login"
    }

    case "login" => {
      config.map(c => ws.map(_ ! SendJs(Json.toJson(Login.from(c)))))
    }

    case LoginSuccess => ws.map(_ ! SendJs(SubscribeReports.toJsValue()))
    case SubsribeReportsSuccess => println("Subscribe Reports success")


    case RPCFailed(id, error) => {
      println(s"Id : ${id}, ${error}")
      context.system.terminate()
    }



    case wsGotText : WsGotText => {
      parser match {
        case Some(parserActor) => parserActor ! wsGotText
        case _ => {println("MainActor : Parser not available")}
      }
    }

  }



}
