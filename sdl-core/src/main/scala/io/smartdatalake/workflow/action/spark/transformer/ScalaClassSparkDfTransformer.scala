/*
 * Smart Data Lake - Build your data lake the smart way.
 *
 * Copyright © 2019-2022 ELCA Informatique SA (<https://www.elca.ch>)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package io.smartdatalake.workflow.action.spark.transformer

import com.typesafe.config.Config
import io.smartdatalake.config.{FromConfigFactory, InstanceRegistry}
import io.smartdatalake.config.SdlConfigObject.{ActionId, DataObjectId}
import io.smartdatalake.util.hdfs.PartitionValues
import io.smartdatalake.util.misc.CustomCodeUtil
import io.smartdatalake.util.spark.DefaultExpressionData
import io.smartdatalake.workflow.ActionPipelineContext
import io.smartdatalake.workflow.action.generic.transformer.{GenericDfTransformer, OptionsSparkDfTransformer}
import io.smartdatalake.workflow.action.spark.customlogic.CustomDfTransformer
import org.apache.spark.sql.{DataFrame, SparkSession}

/**
 * Configuration of a custom Spark-DataFrame transformation between one input and one output (1:1) as Java/Scala Class.
 * Define a transform function which receives a DataObjectId, a DataFrame and a map of options and has to return a
 * DataFrame. The Java/Scala class has to implement interface [[CustomDfTransformer]].
 *
 * @param name           name of the transformer
 * @param description    Optional description of the transformer
 * @param className      class name implementing trait [[CustomDfTransformer]]
 * @param options        Options to pass to the transformation
 * @param runtimeOptions optional tuples of [key, spark sql expression] to be added as additional options when executing transformation.
 *                       The spark sql expressions are evaluated against an instance of [[DefaultExpressionData]].
 */
case class ScalaClassSparkDfTransformer(override val name: String = "scalaSparkTransform", override val description: Option[String] = None, className: String, options: Map[String, String] = Map(), runtimeOptions: Map[String, String] = Map()) extends OptionsSparkDfTransformer {
  private val customTransformer = CustomCodeUtil.getClassInstanceByName[CustomDfTransformer](className)
  override def transformWithOptions(actionId: ActionId, partitionValues: Seq[PartitionValues], df: DataFrame, dataObjectId: DataObjectId, options: Map[String, String])(implicit context: ActionPipelineContext): DataFrame = {
    customTransformer.transform(context.sparkSession, options, df, dataObjectId.id)
  }
  override def transformPartitionValuesWithOptions(actionId: ActionId, partitionValues: Seq[PartitionValues], options: Map[String, String])(implicit context: ActionPipelineContext): Option[Map[PartitionValues,PartitionValues]] = {
   customTransformer.transformPartitionValues(options, partitionValues)
  }
  override def factory: FromConfigFactory[GenericDfTransformer] = ScalaClassSparkDfTransformer
}

object ScalaClassSparkDfTransformer extends FromConfigFactory[GenericDfTransformer] {
  override def fromConfig(config: Config)(implicit instanceRegistry: InstanceRegistry): ScalaClassSparkDfTransformer = {
    extract[ScalaClassSparkDfTransformer](config)
  }
}