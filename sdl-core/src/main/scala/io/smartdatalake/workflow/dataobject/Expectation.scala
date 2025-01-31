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

package io.smartdatalake.workflow.dataobject

import com.typesafe.config.Config
import io.smartdatalake.config.SdlConfigObject.DataObjectId
import io.smartdatalake.config.{ConfigurationException, FromConfigFactory, InstanceRegistry, ParsableFromConfig}
import io.smartdatalake.util.hdfs.PartitionValues
import io.smartdatalake.util.misc.SmartDataLakeLogger
import io.smartdatalake.workflow.ActionPipelineContext
import io.smartdatalake.workflow.dataframe.spark.SparkColumn
import io.smartdatalake.workflow.dataframe.{DataFrameFunctions, GenericColumn}
import io.smartdatalake.workflow.dataobject.ExpectationScope.{ExpectationScope, Job}
import io.smartdatalake.workflow.dataobject.ExpectationSeverity.ExpectationSeverity
import org.apache.spark.sql.Column

/**
 * Trait to define expectations against a dataset.
 * Expectations are checks based on aggregates over all rows of a dataset.
 * Through setting the scope of an expectation, it can be controlled if the aggregate is evaluated per job, partition or the whole content of the table.
 */
trait Expectation extends ParsableFromConfig[Expectation] with SmartDataLakeLogger {
  /**
   * The name of the expectation
   */
  def name: String
  /**
   * Optional detailed description of the expectation
   */
  def description: Option[String]
  /**
   * Severity if expectation fails - can be Error (default) or Warn.
   * If set to Error, execution will fail, otherwise there will be just a warning logged.
   */
  def failedSeverity: ExpectationSeverity
  /**
   * The aggregation scope used to evaluate the aggregate expression.
   * Default is 'Job', which evaluates the records transformed by the current job. This is implemented without big performance impacts on Spark.
   * Other options are 'All' and 'JobPartitions', which are implemented by reading the output data again.
   */
  def scope: ExpectationScope = Job
  def getAggExpressionColumns(dataObjectId: DataObjectId)(implicit functions: DataFrameFunctions): Seq[GenericColumn]

  /**
   * Create columns to validate the expectation and return error message if failed.
   * Can cleanup metrics and return them if artifical metrics have been introduced, like for SQLFractionExpectation.
   */
  def getValidationErrorColumn(dataObjectId: DataObjectId, metrics: Map[String,_], partitionValues: Seq[PartitionValues])(implicit context: ActionPipelineContext): (Seq[SparkColumn],Map[String,_])

  // helpers
  protected def getMetric[T](dataObjectId: DataObjectId, metrics: Map[String,Any], key: String): T = {
    metrics.getOrElse(key, throw new IllegalStateException(s"($dataObjectId) Metric '$key' for expectation ${name} not found for validation")).asInstanceOf[T]
  }
}

case class ExpectationValidationException(msg: String) extends Exception(msg)

/**
 * Definition of aggregation scope used to evaluate an expectations aggregate expression.
 */
// TODO: implement different scopes...
object ExpectationScope extends Enumeration {
  type ExpectationScope = Value

  /**
   * Expectation is evaluated against dataset written to DataObject by the current job.
   * For Spark this can be implemented using Observations, which does not cost performance.
   */
  val Job: Value = Value("Job")

  /**
   * Expectation is evaluated against dataset written to DataObject by the current job, grouped by the partitions processed.
   */
  val JobPartition: Value = Value("JobPartition")

  /**
   * Expectation is evaluated against all data in DataObject.
   */
  val All: Value = Value("All")
}

object ExpectationSeverity extends Enumeration {
  type ExpectationSeverity = Value

  /**
   * Failure of expectation is treated as error.
   */
  val Error: Value = Value("Error")

  /**
   * Failure of expectation is treated as warning.
   */
  val Warn: Value = Value("Warn")
}


/**
 * Definition of expectation based on a SQL aggregate expression to evaluate on dataset-level.
 *
 * @param aggExpression SQL aggregate expression to evaluate on dataset, e.g. count(*).
 * @param expectation Optional SQL comparison operator and literal to define expected value for validation, e.g. '= 0".
 *                    Together with the result of the aggExpression evaluation on the left side, it forms the condition to validate the expectation.
 *                    If no expectation is defined, the aggExpression evaluation result is just recorded in metrics.
 */
case class SQLExpectation(override val name: String, override val description: Option[String] = None, aggExpression: String, expectation: Option[String] = None, override val scope: ExpectationScope = Job, override val failedSeverity: ExpectationSeverity = ExpectationSeverity.Error )
  extends Expectation {
  def getAggExpressionColumns(dataObjectId: DataObjectId)(implicit functions: DataFrameFunctions): Seq[GenericColumn] = {
    try {
      Seq(functions.expr(aggExpression).as(name))
    } catch {
      case e: Exception => throw new ConfigurationException(s"($dataObjectId) Expectation $name: cannot parse SQL aggExpression '$aggExpression'", Some(s"expectations.$name.aggExpression"), e)
    }
  }
  def getValidationColumn(dataObjectId: DataObjectId, value: Any): Option[Column] = {
    import org.apache.spark.sql.functions._
    expectation.map { expectationStr =>
      val valueStr = value match {
        case str: String => s"'$str'"
        case x => x.toString
      }
      val validationExpr = s"$valueStr $expectationStr"
      try {
        expr(validationExpr)
      } catch {
        case e: Exception => throw new ConfigurationException(s"($dataObjectId) Expectation $name: cannot parse validation expression '$validationExpr'", Some(s"expectations.$name"), e)
      }
    }
  }
  def getValidationErrorColumn(dataObjectId: DataObjectId, value: Any, metricName: String = name)(implicit context: ActionPipelineContext): Option[Column] = {
    import org.apache.spark.sql.functions._
    getValidationColumn(dataObjectId, value).map { validationCol =>
      try {
        when(not(validationCol), lit(s"Expectation '$metricName' failed with value:$value expectation:${expectation.get}"))
      } catch {
        case e: Exception => throw new ConfigurationException(s"($dataObjectId) Expectation $name: cannot create validation error column", Some(s"expectations.$name"), e)
      }
    }
  }
  def getValidationErrorColumn(dataObjectId: DataObjectId, metrics: Map[String,_], partitionValues: Seq[PartitionValues])(implicit context: ActionPipelineContext): (Seq[SparkColumn],Map[String,_]) = {
    if (scope == ExpectationScope.JobPartition) {
      val cols = metrics.keys.filter(_.startsWith(name + ExpectationValidation.partitionDelimiter))
        .flatMap { n =>
          val value = getMetric[Any](dataObjectId,metrics,name)
          getValidationErrorColumn(dataObjectId, value, n).map(SparkColumn)
        }.toSeq
      (cols, metrics)
    } else {
      val value = getMetric[Any](dataObjectId,metrics,name)
      val col = getValidationErrorColumn(dataObjectId, value).map(SparkColumn)
      (col.toSeq, metrics)
    }
  }
  override def factory: FromConfigFactory[Expectation] = SQLExpectation
}

object SQLExpectation extends FromConfigFactory[Expectation] {
  override def fromConfig(config: Config)(implicit instanceRegistry: InstanceRegistry): SQLExpectation = {
    extract[SQLExpectation](config)
  }
}

/**
 * Definition of an expectation based on counting how many rows match an expression vs the number of all rows.
 * The fraction of these two counts is compared against a given expectation.
 *
 * @param countConditionExpression SQL expression returning a boolean to match the rows to count for the fraction.
 * @param globalConditionExpression SQL expression returning a boolean used as global filter, e.g. fraction row count and total row count are filtered with global filter before counting.
 * @param expectation Optional SQL comparison operator and literal to define expected percentage for validation, e.g. '= 0.9".
 *                    If no expectation is defined, the result value is just recorded in metrics.
 * @param scope The aggregation scope used to evaluate the aggregate expression.
 *              Default is 'Job', which evaluates the records transformed by the current job. This is implemented without big performance impacts on Spark.
 *              Other options are 'All' and 'JobPartitions', which are implemented by reading the output data again.
 */
case class SQLFractionExpectation(override val name: String, override val description: Option[String] = None, countConditionExpression: String, globalConditionExpression: Option[String] = None, expectation: Option[String] = None, override val scope: ExpectationScope = Job, override val failedSeverity: ExpectationSeverity = ExpectationSeverity.Error )
  extends Expectation {
  private def getGlobalConditionExpression(dataObjectId: DataObjectId)(implicit functions: DataFrameFunctions) = {
    try {
      import functions._
      globalConditionExpression.map(expr).getOrElse(lit(true))
    } catch {
      case e: Exception => throw new ConfigurationException(s"($dataObjectId) Expectation $name: cannot parse SQL expression '${globalConditionExpression.get}'", Some(s"expectations.$name.expression"), e)
    }
  }
  private val totalName = name+"Total"
  def getAggExpressionColumns(dataObjectId: DataObjectId)(implicit functions: DataFrameFunctions): Seq[GenericColumn] = {
    try {
      import functions._
      Seq(count(when(expr(countConditionExpression) and getGlobalConditionExpression(dataObjectId),lit(1))).as(name)) ++
        (if (globalConditionExpression.isDefined) Seq(count(when(getGlobalConditionExpression(dataObjectId),lit(1))).as(totalName)) else Seq())
    } catch {
      case e: Exception => throw new ConfigurationException(s"($dataObjectId) Expectation $name: cannot parse SQL expression '$countConditionExpression'", Some(s"expectations.$name.expression"), e)
    }
  }
  def getValidationColumn(dataObjectId: DataObjectId, fraction: Any): Option[SparkColumn] = {
    import org.apache.spark.sql.functions._
    expectation.map { expectationStr =>
      val validationExpr = s"$fraction $expectationStr"
      try {
        SparkColumn(expr(validationExpr))
      } catch {
        case e: Exception => throw new ConfigurationException(s"($dataObjectId) Expectation $name: cannot parse validation expression '$validationExpr'", Some(s"expectations.$name"), e)
      }
    }
  }
  private def getValidationErrorColumn(dataObjectId: DataObjectId, countExpectation: Long, countTotal: Long, metricName: String = name)(implicit context: ActionPipelineContext): (Option[Column],Any) = {
    import org.apache.spark.sql.functions._
    val pct = if (countTotal == 0) "null" else countExpectation.toFloat / countTotal.toFloat // float precision is sufficient
    val col = getValidationColumn(dataObjectId, pct).map { validationCol =>
      try {
        when(not(validationCol.inner), lit(s"Expectation '$metricName' failed with pct:$pct expectation:${expectation.get}"))
      } catch {
        case e: Exception => throw new ConfigurationException(s"($dataObjectId) Expectation $name: cannot create validation error column", Some(s"expectations.$name"), e)
      }
    }
    // return
    (col,pct)
  }
  def getValidationErrorColumn(dataObjectId: DataObjectId, metrics: Map[String,_], partitionValues: Seq[PartitionValues])(implicit context: ActionPipelineContext): (Seq[SparkColumn],Map[String,_]) = {
    import ExpectationValidation.partitionDelimiter
    val totalMetric = if (globalConditionExpression.isDefined) totalName else "count"
    if (scope == ExpectationScope.JobPartition) {
      val colsAndUpdatedMetrics = metrics.keys.filter(_.startsWith(name + partitionDelimiter)).toSeq
        .map { n =>
          val countExpectation = getMetric[Long](dataObjectId,metrics,n)
          val totalPartitionMetric = (totalMetric +: n.split(partitionDelimiter).drop(1)).mkString(partitionDelimiter)
          val countTotal = getMetric[Long](dataObjectId,metrics,totalPartitionMetric)
          val (col, pct) = getValidationErrorColumn(dataObjectId, countExpectation, countTotal, n)
          (col.map(SparkColumn), Map(n -> pct))
        }
      val cols = colsAndUpdatedMetrics.flatMap(_._1)
      val updatedMetrics = metrics.filterKeys(!_.startsWith(totalName)) ++ colsAndUpdatedMetrics.map(_._2).reduce(_ ++ _)
      (cols, updatedMetrics)
    } else {
      val countExpectation = getMetric[Long](dataObjectId,metrics,name)
      val countTotal = getMetric[Long](dataObjectId,metrics,totalMetric)
      val (col, pct) = getValidationErrorColumn(dataObjectId, countExpectation, countTotal)
      val updatedMetrics = metrics.filterKeys(_ != totalName) + (name -> pct)
      (col.map(SparkColumn).toSeq, updatedMetrics)
    }
  }

  override def factory: FromConfigFactory[Expectation] = SQLFractionExpectation
}

object SQLFractionExpectation extends FromConfigFactory[Expectation] {
  override def fromConfig(config: Config)(implicit instanceRegistry: InstanceRegistry): SQLFractionExpectation = {
    extract[SQLFractionExpectation](config)
  }
}

/**
 * Definition of an expectation based on the number of records.
 *
 * @param expectation Optional SQL comparison operator and literal to define expected value for validation, e.g. '> 100000".
 *                    If no expectation is defined, the result value is is just recorded in metrics.
 * @param scope The aggregation scope used to evaluate the aggregate expression.
 *              Default is 'Job', which evaluates the records transformed by the current job. This is implemented without big performance impacts on Spark.
 *              Other options are 'All' and 'JobPartitions', which are implemented by reading the output data again.
 */
case class CountExpectation(override val name: String = "count", override val description: Option[String] = None, expectation: Option[String] = None, override val scope: ExpectationScope = Job, override val failedSeverity: ExpectationSeverity = ExpectationSeverity.Error )
  extends Expectation {
  def getAggExpressionColumns(dataObjectId: DataObjectId)(implicit functions: DataFrameFunctions): Seq[GenericColumn] = {
    import functions._
    if (scope == ExpectationScope.Job && name == "count") Seq() // count is measured by default for scope = Job
    else Seq(count(col("*")).as(name))
  }
  def getValidationColumn(dataObjectId: DataObjectId, count: Long): Option[SparkColumn] = {
    expectation.map { expectationStr =>
      val validationExpr = s"$count $expectationStr"
      try {
        import org.apache.spark.sql.functions._
        SparkColumn(expr(validationExpr))
      } catch {
        case e: Exception => throw new ConfigurationException(s"($dataObjectId) Expectation $name: cannot parse validation expression '$validationExpr'", Some(s"expectations.$name"), e)
      }
    }
  }
  private def getValidationErrorColumn(dataObjectId: DataObjectId, countMetric: Long, metricName: String = name)(implicit context: ActionPipelineContext): Option[Column] = {
    getValidationColumn(dataObjectId, countMetric).map { validationCol =>
      try {
        import org.apache.spark.sql.functions._
        when(not(validationCol.inner), lit(s"Expectation '$metricName' failed with count:$countMetric expectation:${expectation.get}"))
      } catch {
        case e: Exception => throw new ConfigurationException(s"($dataObjectId) Expectation $name: cannot create validation error column", Some(s"expectations.$name"), e)
      }
    }
  }
  def getValidationErrorColumn(dataObjectId: DataObjectId, metrics: Map[String,_], partitionValues: Seq[PartitionValues])(implicit context: ActionPipelineContext): (Seq[SparkColumn],Map[String,_]) = {
    if (scope == ExpectationScope.JobPartition) {
      val cols = metrics.keys.filter(_.startsWith(name + ExpectationValidation.partitionDelimiter))
        .flatMap { n =>
          val count = getMetric[Long](dataObjectId,metrics,n)
          getValidationErrorColumn(dataObjectId, count, n).map(SparkColumn)
        }.toSeq
      (cols, metrics)
    } else {
      val count = getMetric[Long](dataObjectId,metrics,name)
      val col = getValidationErrorColumn(dataObjectId, count).map(SparkColumn)
      (col.toSeq, metrics)
    }
  }
  override def factory: FromConfigFactory[Expectation] = CountExpectation
}

object CountExpectation extends FromConfigFactory[Expectation] {
  override def fromConfig(config: Config)(implicit instanceRegistry: InstanceRegistry): CountExpectation = {
    extract[CountExpectation](config)
  }
}

/**
 * Definition of an expectation based on the average number of records per partitions.
 *
 * Note that the scope for evaluating this expectation is fixed to Job.
 *
 * @param expectation Optional SQL comparison operator and literal to define expected value for validation, e.g. '> 100000".
 *                    If no expectation is defined, the result value is just recorded in metrics.
 */
case class AvgCountPerPartitionExpectation(override val name: String, override val description: Option[String] = None, expectation: Option[String] = None, override val failedSeverity: ExpectationSeverity = ExpectationSeverity.Error )
  extends Expectation {
  override val scope: ExpectationScope = ExpectationScope.Job
  def getAggExpressionColumns(dataObjectId: DataObjectId)(implicit functions: DataFrameFunctions): Seq[GenericColumn] = Seq() // no special aggregate needed as count is calculated by default
  def getValidationColumn(dataObjectId: DataObjectId, count: Long): Option[SparkColumn] = {
    expectation.map { expectationStr =>
      val validationExpr = s"$count $expectationStr"
      try {
        import org.apache.spark.sql.functions._
        SparkColumn(expr(validationExpr))
      } catch {
        case e: Exception => throw new ConfigurationException(s"($dataObjectId) Expectation $name: cannot parse validation expression '$validationExpr'", Some(s"expectations.$name"), e)
      }
    }
  }
  private def getValidationErrorColumn(dataObjectId: DataObjectId, countMetric: Long, nbOfPartitionValues: Int)(implicit context: ActionPipelineContext): (Option[SparkColumn], Long) = {
    import org.apache.spark.sql.functions._
    val avgCount = countMetric / math.max(nbOfPartitionValues, 1)
    val col = getValidationColumn(dataObjectId, avgCount).map { validationCol =>
      try {
        SparkColumn(when(not(validationCol.inner), lit(s"Expectation '$name' failed with avgCount:$avgCount expectation:${expectation.get}")))
      } catch {
        case e: Exception => throw new ConfigurationException(s"($dataObjectId) Expectation $name: cannot create validation error column", Some(s"expectations.$name"), e)
      }
    }
    (col, avgCount)
  }
  def getValidationErrorColumn(dataObjectId: DataObjectId, metrics: Map[String,_], partitionValues: Seq[PartitionValues])(implicit context: ActionPipelineContext): (Seq[SparkColumn],Map[String,_]) = {
    // needs partition values
    if (partitionValues.nonEmpty) {
      val count: Long = metrics.getOrElse("count", throw new IllegalStateException(s"($dataObjectId) General 'count' metric for expectation ${name} not found for validation"))
        .asInstanceOf[Long]
      val (col,countAvg) = getValidationErrorColumn(dataObjectId, count, partitionValues.size)
      (col.toSeq, metrics + ("countAvgPerPartition" -> countAvg))
    } else {
      logger.warn(s"($dataObjectId) Cannot evaluate AvgCountPerPartitionExpectation '$name' as there are no partition values")
      (Seq(), metrics)
    }
  }
  override def factory: FromConfigFactory[Expectation] = AvgCountPerPartitionExpectation
}

object AvgCountPerPartitionExpectation extends FromConfigFactory[Expectation] {
  override def fromConfig(config: Config)(implicit instanceRegistry: InstanceRegistry): AvgCountPerPartitionExpectation = {
    extract[AvgCountPerPartitionExpectation](config)
  }
}

// TODO: implement primary-key check expectation?

