package com.actionml.router.service

import com.actionml.router.ActorInjectable
import akka.http.scaladsl.model._
import com.actionml.templates.cb.CBEngine
import io.circe.Json
import io.circe.generic.auto._
import io.circe.syntax._
import scaldi.Injector

/**
  *
  *
  * @author The ActionML Team (<a href="http://actionml.com">http://actionml.com</a>)
  * 25.02.17 11:48
  */
trait QueryService extends ActorInjectable

class CBQueryService(implicit inj: Injector) extends QueryService{

  private val engine = inject[CBEngine]

  override def receive: Receive = {
    case GetPrediction(engineId, query) ⇒
      log.debug("Get prediction, {}, {}", engineId, query)
      val (queryResult, errcode) = engine.query(query)
      sender() ! Either.cond(errcode == StatusCodes.OK, queryResult.asJson, errcode)
  }
}

class EmptyQueryService(implicit inj: Injector) extends QueryService{
  override def receive: Receive = {
    case GetPrediction(engineId, query) ⇒
      log.debug("Get prediction, {}, {}", engineId, query)
      sender() ! None
  }
}

sealed trait QueryAction
case class GetPrediction(engineId: String, query: String) extends QueryAction