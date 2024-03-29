/*
 * This file is part of the Linux Variability Modeling Tools (LVAT).
 *
 * Copyright (C) 2010 Steven She <shshe@gsd.uwaterloo.ca>
 *
 * LVAT is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 *
 * LVAT is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public License for
 * more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with LVAT.  (See files COPYING and COPYING.LESSER.)  If not, see
 * <http://www.gnu.org/licenses/>.
 */
package gsd.linux

import org.kiama.rewriting.Rewriter._

case class ConcreteKConfig(root: CMenu) {

  lazy val plainConfigs: List[CConfig] =
    collectl {
      case c: CConfig if !c.isMenuConfig => c
    }(root)

  lazy val menuConfigs: List[CConfig] =
    collectl {
      case c: CConfig if  c.isMenuConfig => c
    }(root)

  lazy val allConfigs: List[CConfig] = plainConfigs ++ menuConfigs

  lazy val choices: List[CChoice] =
    collectl {
      case c: CChoice => c
    }(root)

  lazy val menus: List[CMenu] =
    collectl {
      case m: CMenu => m
    }(root)

  lazy val features: List[CSymbol] =
    allConfigs ++ menus ++ choices

  lazy val ifNodes: List[CIf] =
    collectl {
      case c: CIf => c
    }(root)

  lazy val comments: List[CComment] =
    collectl {
      case c: CComment => c
    }(root)

  /**
   * Set of all identifiers and identifier references in the Kconfig model.
   */
  lazy val identifiers: Set[String] =
  collects {
    case c: CConfig => c.name
    case Id(n) => n
  }(root)

  /**
   * Note: Doesn't handle multiply defined configs
   */
  lazy val configMap: Map[String, CConfig] =
    allConfigs map { c => c.name -> c } toMap

  lazy val nodeIdMap: Map[Int, CSymbol] =
    (features map { f => f.nodeId -> f }).toMap

  lazy val undefinedConfigs: Set[String] =
    identifiers -- (allConfigs map (_.name))

  lazy val toAbstractKConfig =
    new AbstractSyntax.AbstractSyntaxBuilder(this).toAbstractSyntax
}

case class AbstractKConfig(configs: List[AConfig] = Nil,
                           choices: List[AChoice] = Nil,
                           parentMap: Map[AConfig, AConfig] = Map(),
                           env: List[String] = Nil) {

  lazy val identifiers =
    AbstractKConfig.identifiers(configs ::: choices)

  lazy val idMap: Map[String, Int] =
    (identifiers.toList.zipWithIndex map { case (name, i) => (name, i+1) }).toMap


  val sDefault: Term => List[List[(String, KExpr)]] = collectl {
    case c: AConfig if
    c.ktype == KStringType ||
      c.ktype == KIntType ||
      c.ktype == KHexType => c.defs map {
      case ADefault(ex, _, _) => c.name -> ex
    }
  }


  // TODO handle eq / neq between two variables that are non-bool
  lazy val literalExprs: Set[(String, KExpr)] = {
    val sExpr = collectl {
      case Eq(lit@Literal(_), Id(name))  => name -> lit
      case Eq(Id(name), lit@Literal(_))  => name -> lit

      case NEq(lit@Literal(_), Id(name)) => name -> lit
      case NEq(Id(name), lit@Literal(_)) => name -> lit
    }

    ((configs flatMap sExpr) ::: (choices flatMap sExpr) :::
     (configs flatMap sDefault).flatten[(String, KExpr)]).toSet
  }

  /**
   * Helper function for finding a particular config, probably belongs elsewhere
   */
  def findConfig(findName: String): Option[AConfig] =
    configs find (_.name == findName)

  def findAllConfigs(findName: String): List[AConfig] =
    configs filter (_.name == findName)
}

object AbstractKConfig {
  implicit def fromConcreteKConfig(k: ConcreteKConfig): AbstractKConfig =
    k.toAbstractKConfig

  def identifiers(in: Term): Set[String] =
    collects {
      case c: AConfig => c.name
      case Id(n) => n
    }(in)

}

sealed abstract class CSymbol(val nodeId: Int, // The unique node identifier
                              val properties: List[Property],
                              val isVirtual: Boolean, // Whether the node is a real symbol or not (i.e. if node)
                              val children: List[CSymbol]) {
  def prettyString: String
}
sealed abstract class ASymbol

/* ~~~~~~~~~~~~~~~
 * Abstract Syntax
 * - Properties are expanded such that order doesn't matter
 * - Prompt condition are combined through disjunction
 * - Selects are converted to reverse-dependencies
 * ~~~~~~~~~~~~~~~ */
case class AConfig(nodeId: Int,
                   name: String,
                   ktype: KType = KBoolType,
                   inherited: KExpr = Yes, // Defines the upper-bound for this config
                   pro: KExpr = Yes,
                   defs: List[ADefault] = Nil,
                   rev: List[KExpr] = Nil, // A disjunction of conditions, the lower-bound
                   ranges: List[Range] = Nil,
                   modOnly: Boolean = false) // depends on .. && m
        extends ASymbol

case class AChoice(vis: KExpr = Yes,
                   isBool: Boolean,
                   isMand: Boolean,
                   memIds: List[String])
        extends ASymbol

case class ADefault(iv: KExpr,
                    prevConditions: List[KExpr],
                    currCondition: KExpr)

/* ~~~~~~~~~~~~~~~
 * Concrete Syntax
 * ~~~~~~~~~~~~~~~ */

case class CConfig(nId: Int,
                   name: String,
                   isMenuConfig: Boolean = false,
                   ktype: KType = KBoolType,
                   inherited: KExpr = Yes,
                   prompt: List[Prompt] = Nil,
                   defs: List[Default] = Nil,
                   sels: List[Select] = Nil,
                   ranges: List[Range] = Nil,
                   env: List[Env] = Nil,
                   depends: List[DependsOn] = Nil,
                   cs: List[CSymbol] = Nil)
        extends CSymbol(nId, prompt.toList ::: defs ::: sels ::: ranges, false, cs) {
  override def prettyString = name
}

case class CMenu(nId: Int,
                 prompt: Prompt,
                 cs: List[CSymbol] = Nil)
        extends CSymbol(nId, List(prompt), false, cs) {
  override def prettyString = prompt.text
}

case class CChoice(nId: Int,
                   prompt: Prompt,
                   isBool: Boolean,
                   isMand: Boolean,
                   defs: List[Default] = Nil,
                   depends: List[DependsOn] = Nil,
                   cs: List[CConfig] = Nil)
        extends CSymbol(nId, prompt :: defs, false, cs) {
  override def prettyString = prompt.text
}

case class CIf(nId: Int, condition: KExpr, cs: List[CSymbol] = Nil) extends CSymbol(nId, Nil, true, cs) {
  override def prettyString = "If " + condition
}

case class CComment(nId: Int, text: String, condition: KExpr) extends CSymbol(nId, Nil, true, Nil) {
  override def prettyString = text
}

sealed abstract class Property(val cond: KExpr)
case class Prompt(text: String, c: KExpr = Yes) extends Property(c)
case class Default(iv: KExpr, c: KExpr = Yes) extends Property(c)
case class Select(id: String, c: KExpr = Yes) extends Property(c)
case class Range(low: IdOrValue, high: IdOrValue, c: KExpr = Yes) extends Property(c)
case class Env(id: String, c: KExpr = Yes) extends Property(c)

case class DependsOn(cond: KExpr)

sealed abstract class KType
case object KBoolType extends KType
case object KTriType extends KType
case object KHexType extends KType
case object KIntType extends KType
case object KStringType extends KType
