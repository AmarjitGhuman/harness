/*
 * Copyright ActionML, LLC under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * ActionML licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.actionml.engines.urnavhinting

import cats.data.Validated
import cats.data.Validated.Valid
import com.actionml.core.drawInfo
import com.actionml.core.engine.{Engine, QueryResult}
import com.actionml.core.model.{EngineParams, Event, GenericQuery, Query}
import com.actionml.core.store.backends.MongoStorage
import com.actionml.core.validate.ValidateError
import com.actionml.engines.urnavhinting.URNavHintingEngine.{URNavHintingEngineParams, URNavHintingEvent, URNavHintingQuery}
import com.actionml.engines.urnavhinting.URNavHintingAlgorithm
import org.json4s.JValue

class URNavHintingEngine extends Engine {

  private var dataset: URNavHintingDataset = _
  private var algo: URNavHintingAlgorithm = _
  private var params: URNavHintingEngineParams = _

  /** Initializing the Engine sets up all needed objects */
  override def init(jsonConfig: String, deepInit: Boolean = true): Validated[ValidateError, Boolean] = {
    super.init(jsonConfig).andThen { _ =>

      parseAndValidate[URNavHintingEngineParams](jsonConfig).andThen { p =>
        params = p
        engineId = params.engineId
        val dbName = p.sharedDBName.getOrElse(engineId)
        dataset = new URNavHintingDataset(engineId = engineId, store = MongoStorage.getStorage(dbName, MongoStorageHelper.codecs))
        algo = URNavHintingAlgorithm(this, jsonConfig, dataset)
        drawInfo("Generic NavHinting Engine", Seq(
          ("════════════════════════════════════════", "══════════════════════════════════════"),
          ("EngineId: ", engineId)))

        Valid(p)
      }.andThen { p =>
        dataset.init(jsonConfig).andThen { r =>
          algo.init(this)
        }
      }
    }
  }

  // Used starting Harness and adding new engines, persisted means initializing a pre-existing engine. Only called from
  // the administrator.
  // Todo: This method for re-init or new init needs to be refactored, seem ugly
  // Todo: should return null for bad init
  override def initAndGet(jsonConfig: String): URNavHintingEngine = {
    val response =init(jsonConfig)
    if (response.isValid) {
      logger.trace(s"Initialized with JSON: $jsonConfig")
      this
    } else {
      logger.error(s"Parse error with JSON: $jsonConfig")
      null.asInstanceOf[URNavHintingEngine] // todo: ugly, replace
    }
  }

  override def input(jsonEvent: String): Validated[ValidateError, Boolean] = {
    logger.trace("Got JSON body: " + jsonEvent)
    // validation happens as the input goes to the dataset
    //super.input(jsonEvent).andThen(_ => dataset.input(jsonEvent)).andThen { _ =>
    super.input(jsonEvent).andThen(_ => dataset.input(jsonEvent)).andThen { _ =>
      parseAndValidate[URNavHintingEvent](jsonEvent).andThen(algo.input)
    }
    //super.input(jsonEvent).andThen(dataset.input(jsonEvent)).andThen(algo.input(jsonEvent)).map(_ => true)
  }

  // todo: should merge base engine status with UREngine's status
  override def status(): Validated[ValidateError, String] = {
    logger.trace(s"Status of URNavHintingEngine with engineId:$engineId")
    Valid(this.params.toString)
  }

  /** triggers parse, validation of the query then returns the result with HTTP Status Code */
  def query(jsonQuery: String): Validated[ValidateError, String] = {
    logger.trace(s"Got a query JSON string: $jsonQuery")
    parseAndValidate[URNavHintingQuery](jsonQuery).andThen { query =>
      // query ok if training group exists or group params are in the dataset
      val result = algo.query(query)
      Valid(result.toJson)
    }
  }

  // todo: should kill any pending Spark jobs
  override def destroy(): Unit = {
    logger.info(s"Dropping persisted data for id: $engineId")
    dataset.destroy()
    algo.destroy()
  }

}

object URNavHintingEngine {
  def apply(jsonConfig: String): URNavHintingEngine = {
    val engine = new URNavHintingEngine()
    engine.initAndGet(jsonConfig)
  }

  case class URNavHintingEngineParams(
      engineId: String, // required, resourceId for engine
      engineFactory: String,
      mirrorType: Option[String] = None,
      mirrorContainer: Option[String] = None,
      sharedDBName: Option[String] = None,
      sparkConf: Map[String, JValue])
    extends EngineParams

  case class URNavHintingEvent (
      //eventId: String, // not used in Harness, but allowed for PIO compatibility
      event: String,
      entityType: String,
      entityId: String,
      targetEntityId: Option[String] = None,
      properties: Map[String, Boolean] = Map.empty,
      conversionId: Option[String] = None, // only set when copying converted journey's where event = nav-event
      eventTime: String) // ISO8601 date
    extends Event

  case class ItemProperties (
      _id: String, // must be the same as the targetEntityId for the $set event that changes properties in the model
      properties: Map[String, Any] // properties to be written to the model, this is saved in the input dataset
  )

  case class URNavHintingQuery(
      userId: Option[String], // ignored for non-personalized
      eligibleNavIds: Array[String])
    extends Query

  case class UrNavHintingQueryResult(
      navHints: Array[(String, Double)] = Array.empty)
    extends QueryResult {

    def toJson: String = {
      val jsonStart =
        s"""
           |{
           |  "results": [
        """.stripMargin
      val jsonMiddle = navHints.map{ case (k, v) =>
        s"""
           |   {$k, $v},
       """.stripMargin
      }.mkString
      val jsonEnd =
        s"""
           |  ]
           |}
        """.stripMargin
      val retVal = jsonStart + jsonMiddle + jsonEnd
      retVal
    }
  }



}
