/**
  * (C) Copyright IBM Corp. 2015 - 2017
  *
  * Licensed under the Apache License, Version 2.0 (the "License");
  * you may not use this file except in compliance with the License.
  * You may obtain a copy of the License at
  *
  *     http://www.apache.org/licenses/LICENSE-2.0
  *
  * Unless required by applicable law or agreed to in writing, software
  * distributed under the License is distributed on an "AS IS" BASIS,
  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  * See the License for the specific language governing permissions and
  * limitations under the License.
  *
  */

package com.ibm.sparktc.sparkbench.sparklaunch

import java.util

import com.typesafe.config.{Config, ConfigValueFactory}

import scala.collection.JavaConverters._
import com.ibm.sparktc.sparkbench.sparklaunch.{SparkLaunchDefaults => SLD}
import com.ibm.sparktc.sparkbench.utils.TypesafeAccessories.{configToMapStringAny, splitGroupedConfigToIndividualConfigs}

import scala.util.Try

case class LaunchConfigDeconstructedWithSeqs(
                                              sparkSubmitOptions: Map[String, Seq[Any]],
                                              suitesConfig: Config
                                            ) {

  def split(): Seq[LaunchConfigDeconstructed] = {
    val splitMaps: Seq[Map[String, Any]] = splitGroupedConfigToIndividualConfigs(sparkSubmitOptions)
    val asJava: Seq[util.Map[String, Any]] = splitMaps.map(_.asJava)

    asJava.map(LaunchConfigDeconstructed(_, suitesConfig))
  }
}

object LaunchConfigDeconstructedWithSeqs {

  def apply(oneSparkSubmitConfig: Config): LaunchConfigDeconstructedWithSeqs = {
    val suites = oneSparkSubmitConfig.withOnlyPath(SLD.suites)
    val workingConf = oneSparkSubmitConfig.withoutPath(SLD.suites)
    val map: Map[String, Seq[Any]] = configToMapStringAny(workingConf)

    val newMap = extractSparkArgsToHigherLevel(map, workingConf)

    LaunchConfigDeconstructedWithSeqs(
      newMap,
      suites
    )
  }

  private def extractSparkArgsToHigherLevel(map: Map[String, Seq[Any]], workingConf: Config): Map[String, Seq[Any]] = {
    if(map.contains("spark-args")) {
      val mapStripped = map - "spark-args"
      val justSparkArgs = workingConf.withOnlyPath("spark-args").getObject("spark-args").toConfig
      val sparkArgsMap = configToMapStringAny(justSparkArgs)

      val newMap = mapStripped ++ sparkArgsMap
      newMap
    }
    else map
  }
}

case class LaunchConfigDeconstructed(
                                      sparkSubmitOptions: util.Map[String, Any],
                                      suitesConfig: Config
                                    ) {
  def splitPieces: SparkSubmitPieces = {

    def optionMapToJava[K, V](m: Option[Map[K, V]]) = m match {
      case Some(map) => Some(map.asJava)
      case _ => None
    }

    def optionallyGetFromJavaMap[A](m: util.Map[String, Any], key: String): Option[A] = {
      if (m.containsKey(key))
        Some(m.get(key).asInstanceOf[A])
      else None
    }

    val sparkHome = optionallyGetFromJavaMap[String](sparkSubmitOptions, SLD.sparkHome)
    val suitesParallel = optionallyGetFromJavaMap[Boolean](sparkSubmitOptions, SLD.suitesParallel)
    val conf: Option[util.Map[String, Any]] = optionallyGetFromJavaMap[util.Map[String, Any]](sparkSubmitOptions, SLD.sparkConf)

    val sparkArgs: Option[util.Map[String, Any]] = {
      val asScala = sparkSubmitOptions.asScala
      val filtered = asScala.filterKeys {
        case SLD.sparkConf => false
        case SLD.suitesParallel => false
        case SLD.sparkHome => false
        case _ => true
      }

      if (filtered.isEmpty) None
      else Some(filtered.asJava)
    }

    SparkSubmitPieces(
      sparkHome,
      suitesParallel,
      conf,
      sparkArgs,
      suitesConfig
    )
  }
}

case class SparkSubmitPieces (
                               sparkHome: Option[String],
                               suitesParallel: Option[Boolean],
                               conf: Option[util.Map[String, Any]],
                               sparkArgs: Option[util.Map[String, Any]],
                               suitesConfig: Config
                             ) {
  def reconstruct: Config = {

//    val confToJava = conf match {case Some(m) => Some(m.asJava); case None => None}

    def ifItsThere[A](key: String, option: Option[A]): Option[(String, A)] =
      Try(key -> option.get).toOption

    val mostOfIt = Seq(
      ifItsThere(SLD.sparkHome, sparkHome),
      ifItsThere(SLD.suitesParallel, suitesParallel),
      ifItsThere(SLD.sparkConf, conf),
      ifItsThere(SLD.sparkArgs, sparkArgs)
    ).flatten.toMap.asJava

    val sparkSubmitConf = ConfigValueFactory.fromMap(mostOfIt)

    val ret = sparkSubmitConf.withFallback(suitesConfig)
    ret.toConfig
  }
}