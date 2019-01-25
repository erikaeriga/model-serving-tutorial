/*
 * Copyright (C) 2019  Lightbend
 *
 * This file is part of ModelServing-tutorial
 *
 * ModelServing-tutorial is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.lightbend.modelserving.tensorflowserving

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter._
import akka.actor.typed.{ActorRef, ActorSystem}
import akka.http.scaladsl.Http
import akka.kafka.scaladsl.Consumer
import akka.kafka.{ConsumerSettings, Subscriptions}
import akka.stream.scaladsl.Sink
import akka.stream.typed.scaladsl.{ActorFlow, ActorMaterializer}
import akka.util.Timeout
import com.lightbend.modelserving.configuration.ModelServingConfiguration
import com.lightbend.modelserving.model.{ModelToServe, ServingResult}
import com.lightbend.modelserving.winemodel.{DataRecord, WineFactoryResolver}
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.serialization.ByteArrayDeserializer

import scala.concurrent.duration._
import scala.util.Success

object TFServingModelServer {

  import ModelServingConfiguration._

  // Initialization

  implicit val modelServer = ActorSystem(
    Behaviors.setup[TFModelServerActor](
      context => new TFModelServerBehaviour(context)), "ModelServing")

  implicit val materializer = ActorMaterializer()
  implicit val executionContext = modelServer.executionContext
  implicit val askTimeout = Timeout(30.seconds)

  // Sources
  val dataSettings = ConsumerSettings(modelServer.toUntyped, new ByteArrayDeserializer, new ByteArrayDeserializer)
    .withBootstrapServers(KAFKA_BROKER)
    .withGroupId(DATA_GROUP)
    .withProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")

  def main(args: Array[String]): Unit = {

    println(s"Akka model server, brokers $KAFKA_BROKER")

    // Data stream processing
    Consumer.atMostOnceSource(dataSettings, Subscriptions.topics(DATA_TOPIC))
      .map(record => DataRecord.wineFromByteArray(record.value)).collect { case Success(a) => a }
      .via(ActorFlow.ask(1)(modelServer)((elem, replyTo : ActorRef[Option[ServingResult[Double]]]) => new ServeData(replyTo, elem)))
      .collect{ case (Some(result)) => result}
      .runWith(Sink.foreach(result =>
        println(s"Model serving in ${System.currentTimeMillis() - result.duration} ms, with result ${result.result} " +
          s"(model ${result.name}, data type ${result.dataType})")))
    // Rest Server
    startRest(modelServer)
  }

  def startRest(modelServerManager: ActorSystem[TFModelServerActor]): Unit = {

    implicit val timeout = Timeout(10.seconds)
    implicit val system = modelServerManager.toUntyped

    val host = "0.0.0.0"
    val port = MODELSERVING_PORT
    val routes = TFQueriesAkkaHttpResource.storeRoutes(modelServerManager)(modelServerManager.scheduler)

    val _ = Http().bindAndHandle(routes, host, port) map
      { binding =>
        println(s"Starting models observer on port ${binding.localAddress}") } recover {
      case ex =>
        println(s"Models observer could not bind to $host:$port - ${ex.getMessage}")
    }
  }
}
