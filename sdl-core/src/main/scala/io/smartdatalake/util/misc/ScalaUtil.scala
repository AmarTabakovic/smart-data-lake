/*
 * Smart Data Lake - Build your data lake the smart way.
 *
 * Copyright © 2019-2021 ELCA Informatique SA (<https://www.elca.ch>)
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

package io.smartdatalake.util.misc

import scala.reflect.runtime.currentMirror
import scala.reflect.runtime.universe._
import scala.util.{Failure, Success, Try}

object ScalaUtil {

  /**
   * Get companion object instance for generic type 
   * 
   * @tparam T class type to get the companion object for
   * @tparam CT interface that the companion object implements 
   * @return instance of companion object implementing given interface CT
   */
  def companionOf[T: TypeTag, CT]: CT = {
    Try {
      val companionModule = typeTag[T].tpe.typeSymbol.companion.asModule
      currentMirror.reflectModule(companionModule).instance.asInstanceOf[CT]
    } match {
      case Success(c) => c
      case Failure(ex) => throw new RuntimeException(s"Could not get companion object for type ${typeTag[T]}: ${ex.getClass.getSimpleName} ${ex.getMessage}")
    }
  }

  /**
   * Get companion object instance for given type
   *
   * @tparam CT interface that the companion object implements
   * @return instance of companion object implementing given interface CT
   */
  def companionOf[CT](tpe: Type): CT = {
    Try {
      val companionModule = tpe.typeSymbol.asClass.companion.asModule
      currentMirror.reflectModule(companionModule).instance.asInstanceOf[CT]
    } match {
      case Success(c) => c
      case Failure(ex) => throw new RuntimeException(s"Could not get companion object for type ${tpe.typeSymbol.name}: ${ex.getClass.getSimpleName} ${ex.getMessage}")
    }
  }

  /**
   * Get companion object instance for given class name
   *
   * @tparam CT interface that the companion object implements
   * @return instance of companion object implementing given interface CT
   */
  def companionOf[CT](className: String): CT = {
    Try {
      val companionModule = currentMirror.staticModule(className)
      currentMirror.reflectModule(companionModule).instance.asInstanceOf[CT]
    } match {
      case Success(c) => c
      case Failure(ex) => throw new RuntimeException(s"Could not get companion object for type ${className}: ${ex.getClass.getSimpleName} ${ex.getMessage}")
    }
  }

  /**
   * Return None if given Map is empty, otherwise Some(map). 
   */
  def optionalizeMap(m: Map[String,String]): Option[Map[String,String]] = if (m.isEmpty) None else Some(m)

  def arrayToSeq[T](arr: Array[T]): Seq[T] = if (arr == null) Seq() else arr.toSeq

  /**
   * extract case class attributes with values through reflection
   */
  def attributesWithValuesForCaseClass(obj: Any): Seq[(String, Any)] = {
    val clsSym = mirror.classSymbol(obj.getClass)
    val inst = mirror.reflect(obj)
    val attributes = clsSym.toType.members.collect { case m: MethodSymbol if m.isCaseAccessor => m }
    attributes.map { m =>
      val key = m.name.toString
      val value = inst.reflectMethod(m).apply()
      (key, value)
    }.toSeq
  }

  private val mirror = scala.reflect.runtime.currentMirror

}
