package org.jetbrains.plugins.scala.lang.parser.parsing.expressions
/**
* @author Ilya Sergey
*/
import com.intellij.lang.PsiBuilder, org.jetbrains.plugins.scala.lang.lexer.ScalaTokenTypes
import org.jetbrains.plugins.scala.lang.parser.ScalaElementTypes
import org.jetbrains.plugins.scala.lang.lexer.ScalaElementType
import org.jetbrains.plugins.scala.lang.parser.bnf.BNF
import com.intellij.psi.tree.TokenSet
import com.intellij.psi.tree.IElementType
import org.jetbrains.plugins.scala.lang.parser.util.ParserUtils
import org.jetbrains.plugins.scala.lang.parser.parsing.types._
import org.jetbrains.plugins.scala.lang.parser.parsing.patterns._

object CompositeExpr {
/*
Composite Expression
Default grammar
Expr1 ::=   if �(� Expr1 �)� [NewLine] Expr [[�;�] else Expr]                           (if)
          | try �{� Block �}� [catch �{� CaseClauses �}�] [finally Expr]                 (try)
          | while �(� Expr �)� [NewLine] Expr                                          (while)
          | do Expr [StatementSeparator] while �(� Expr �)�                            (do)
          | for (�(� Enumerators �)� | �{� Enumerators �}�)[NewLine] [yield] Expr        (for)
          | throw Expr                                                               (throw)
          | return [Expr]                                                            (return)
          | [SimpleExpr �.�] id �=� Expr                                               (b2)
          | SimpleExpr ArgumentExprs �=� Expr                                         (b1)
          | PostfixExpr [�:� Type1]                                                   (a)
          | PostfixExpr match �{� CaseClauses �}�                                      (a1)
          | MethodClosure                                                            (closure)
*/

  def parse(builder : PsiBuilder) : ScalaElementType = {
    val compMarker = builder.mark()

    /* Error processing */
    def errorDoneMain(rollbackMarker: PsiBuilder.Marker,
                      elem: ScalaElementType) = {
      def errorDone (msg: String): ScalaElementType = {
        rollbackMarker.drop()
        builder.error(msg)
        compMarker.done(elem)
        ScalaElementTypes.EXPR1
      }
      (msg:String) => errorDone(msg)
    }

/******************************************************************/
/*********************** Various cases ****************************/
/******************************************************************/

/****************************** case (a), (a1) ****************************/
    def aCase: ScalaElementType = {
      val rollbackMarker = builder.mark() // marker to rollback
      var result = PostfixExpr.parse(builder)
      if (!result.equals(ScalaElementTypes.WRONGWAY)) {
        builder getTokenType match {
          /*    [�:� Type1]   */
          case ScalaTokenTypes.tCOLON => {
            ParserUtils.eatElement(builder, ScalaTokenTypes.tCOLON)
            val res = Type1 parse (builder)
            res match {
              case ScalaElementTypes.TYPE1 => {
                rollbackMarker.drop()
                compMarker.done(ScalaElementTypes.TYPED_EXPR_STMT)
                ScalaElementTypes.EXPR1
              }
              case _ => {
                rollbackMarker.rollbackTo()
                ScalaElementTypes.WRONGWAY
              }
            }
          }
          /* match �{� CaseClauses �}� */
          case ScalaTokenTypes.kMATCH => {
            ParserUtils.eatElement(builder, ScalaTokenTypes.kMATCH)
            if (builder.getTokenType.eq(ScalaTokenTypes.tLBRACE)) {
              ParserUtils.eatElement(builder, ScalaTokenTypes.tLBRACE)
              var result = CaseClauses.parse(builder)
              if (ScalaElementTypes.CASE_CLAUSES.equals(result)) {
                if (builder.getTokenType.eq(ScalaTokenTypes.tRBRACE)){
                  ParserUtils.eatElement(builder, ScalaTokenTypes.tRBRACE)
                  rollbackMarker.drop()
                  compMarker.done(ScalaElementTypes.MATCH_STMT)
                  ScalaElementTypes.EXPR1
                } else {
                  builder.error("} expected")
                  rollbackMarker.drop()
                  compMarker.done(ScalaElementTypes.MATCH_STMT)
                  ScalaElementTypes.EXPR1
                }
              } else {
                builder.error("Case clauses expected")
                rollbackMarker.drop()
                compMarker.done(ScalaElementTypes.MATCH_STMT)
                ScalaElementTypes.EXPR1
              }
            } else {
               rollbackMarker.rollbackTo()
               ScalaElementTypes.WRONGWAY
            }
          }
          case _ => {
            rollbackMarker.drop()
            if (ScalaElementTypes.SIMPLE_EXPR.equals(result)){
              compMarker.done (ScalaElementTypes.EXPR)
            }
            else compMarker.drop
            ScalaElementTypes.EXPR1
          }
        }
      } else {
        rollbackMarker.rollbackTo()
        ScalaElementTypes.WRONGWAY
      }
    }

/*********************** cases (b1), (b2) **************************/
    def bCase: ScalaElementType = {
      var rollbackMarker = builder.mark() // marker to rollback

      def assignProcess: ScalaElementType = {
        ParserUtils.eatElement(builder , ScalaTokenTypes.tASSIGN)
        var res = Expr.parse(builder)
        if (res.eq(ScalaElementTypes.EXPR)) {
          rollbackMarker.drop()
          compMarker.done (ScalaElementTypes.ASSIGN_STMT)
          ScalaElementTypes.EXPR1
        } else {
          rollbackMarker.drop()
          builder.error("Expression expected")
          compMarker.done (ScalaElementTypes.ASSIGN_STMT)
          ScalaElementTypes.EXPR1
        }
      }

      def processSimpleExpr: ScalaElementType = {
        var res = SimpleExpr.parse(builder)
        if (res.parsed.eq(ScalaElementTypes.SIMPLE_EXPR) &&
            ( res.endness.eq("argexprs") || res.endness.eq(".id") ) ) {
          if (builder.getTokenType.eq(ScalaTokenTypes.tASSIGN)) {
          assignProcess
          } else {
            rollbackMarker.rollbackTo()
            ScalaElementTypes.WRONGWAY
          }
        } else {
          rollbackMarker.rollbackTo()
          ScalaElementTypes.WRONGWAY
        }
      }

      if (builder.getTokenType.eq(ScalaTokenTypes.tIDENTIFIER)) {
        ParserUtils.eatElement(builder , ScalaTokenTypes.tIDENTIFIER)
        if (builder.getTokenType.eq(ScalaTokenTypes.tASSIGN)) {
          assignProcess
        } else {
          rollbackMarker.rollbackTo()
          rollbackMarker = builder.mark()
          processSimpleExpr
        }
      } else if (builder.getTokenType.eq(ScalaTokenTypes.kTHIS) ||
                 builder.getTokenType.eq(ScalaTokenTypes.kSUPER)){
        processSimpleExpr
      } else {
        rollbackMarker.rollbackTo()
        ScalaElementTypes.WRONGWAY
      }
    }


/******************************* case (if) ****************************/
    def ifCase: ScalaElementType = {
      val rollbackMarker = builder.mark() // marker to rollback

      /* for mistakes processing */
      def errorDone = errorDoneMain(rollbackMarker, ScalaElementTypes.IF_STMT )

      def elseProcessing: ScalaElementType = {
        if (builder.getTokenType.eq(ScalaTokenTypes.tSEMICOLON)){
          ParserUtils.eatElement(builder, ScalaTokenTypes.tSEMICOLON)
        }
        if (builder.getTokenType.eq(ScalaTokenTypes.kELSE)) {
          ParserUtils.eatElement(builder, ScalaTokenTypes.kELSE)
          val res2 = Expr.parse(builder)
          if (res2.eq(ScalaElementTypes.EXPR)){
            rollbackMarker.drop()
            compMarker.done(ScalaElementTypes.IF_STMT)
            ScalaElementTypes.EXPR1
          } else errorDone("Wrong expression")                   
        } else errorDone("else expected")
      }

      if (builder.getTokenType.eq(ScalaTokenTypes.kIF)){
        ParserUtils.eatElement(builder, ScalaTokenTypes.kIF)
        if (builder.getTokenType.eq(ScalaTokenTypes.tLPARENTHIS)){
          ParserUtils.eatElement(builder, ScalaTokenTypes.tLPARENTHIS)
          val res = Expr parse(builder)
          if (res.eq(ScalaElementTypes.EXPR)){
            if (builder.getTokenType.eq (ScalaTokenTypes.tRPARENTHIS)){
              ParserUtils.eatElement(builder, ScalaTokenTypes.tRPARENTHIS)
              // Warning!
              ParserUtils.rollForward(builder)
              val res1 = Expr.parse(builder)
              if (res1.eq(ScalaElementTypes.EXPR)){
                var mileMarker = builder.mark()
                builder.getTokenType match {
                  case ScalaTokenTypes.kELSE | ScalaTokenTypes.tSEMICOLON => {
                    mileMarker.drop()
                    elseProcessing
                  }
                  case _ => {
                    mileMarker.rollbackTo()
                    rollbackMarker.drop()
                    compMarker.done(ScalaElementTypes.IF_STMT)
                    ScalaElementTypes.EXPR1
                  }
                }
              } else errorDone("Wrong expression")
            } else errorDone(" ) expected")
          } else errorDone("Wrong expression")
        } else errorDone("( expected")

      } else {
        rollbackMarker.rollbackTo()
        ScalaElementTypes.WRONGWAY
      }
    }


/***************************** case (while) *************************/
    def whileCase: ScalaElementType = {
      val rollbackMarker = builder.mark() // marker to rollback

      /* for mistakes processing */
      def errorDone = errorDoneMain(rollbackMarker , ScalaElementTypes.WHILE_STMT)
      if (builder.getTokenType.eq(ScalaTokenTypes.kWHILE)){
        ParserUtils.eatElement(builder, ScalaTokenTypes.kWHILE)
        if (builder.getTokenType.eq(ScalaTokenTypes.tLPARENTHIS)){
          ParserUtils.eatElement(builder, ScalaTokenTypes.tLPARENTHIS)
          val res = Expr parse(builder)
          if (res.eq(ScalaElementTypes.EXPR)){
            if (builder.getTokenType.eq (ScalaTokenTypes.tRPARENTHIS)){
              ParserUtils.eatElement(builder, ScalaTokenTypes.tRPARENTHIS)
              // Warning!
              ParserUtils.rollForward(builder)
              val res1 = Expr.parse(builder)
              if (res1.eq(ScalaElementTypes.EXPR)){
                rollbackMarker.drop()
                compMarker.done(ScalaElementTypes.WHILE_STMT)
                ScalaElementTypes.EXPR1
              } else errorDone("Wrong expression")
            } else errorDone(" ) expected")
          } else errorDone("Wrong expression")
        } else errorDone("( expected")

      } else {
        rollbackMarker.rollbackTo()
        ScalaElementTypes.WRONGWAY
      }
    }

/***************************** case (do) *************************/

    def doCase: ScalaElementType = {
      val rollbackMarker = builder.mark() // marker to rollback

      /* for mistakes processing */
      def errorDone = errorDoneMain(rollbackMarker , ScalaElementTypes.DO_STMT)

      def whileProcessing = {
        if (builder.getTokenType.eq(ScalaTokenTypes.kWHILE)){
          ParserUtils.eatElement(builder, ScalaTokenTypes.kWHILE)
          if (builder.getTokenType.eq(ScalaTokenTypes.tLPARENTHIS)){
            ParserUtils.eatElement(builder, ScalaTokenTypes.tLPARENTHIS)
            val res = Expr parse(builder)
            if (res.eq(ScalaElementTypes.EXPR)){
              if (builder.getTokenType.eq (ScalaTokenTypes.tRPARENTHIS)){
                ParserUtils.eatElement(builder, ScalaTokenTypes.tRPARENTHIS)
                rollbackMarker.drop()
                compMarker.done(ScalaElementTypes.DO_STMT)
                ScalaElementTypes.EXPR1
              } else errorDone(" ) expected")
            } else errorDone("Wrong expression")
          } else errorDone("( expected")
        } else errorDone("while expected")
      }

      if (builder.getTokenType.eq(ScalaTokenTypes.kDO)){
        ParserUtils.eatElement(builder, ScalaTokenTypes.kDO)
        val res1 = Expr.parse(builder)
        if (res1.eq(ScalaElementTypes.EXPR)){
          builder.getTokenType match {
            case  ScalaTokenTypes.tLINE_TERMINATOR
                | ScalaTokenTypes.tSEMICOLON => ParserUtils.eatElement(builder, builder.getTokenType )
            case _ =>
          }
          whileProcessing
        } else errorDone("Wrong expression")
      } else {
        rollbackMarker.rollbackTo()
        ScalaElementTypes.WRONGWAY
      }
    }

/******************** case (for) ************************/

    def forCase: ScalaElementType = {
      val rollbackMarker = builder.mark() // marker to rollback

      /* for mistakes processing */
      def errorDone = errorDoneMain(rollbackMarker , ScalaElementTypes.FOR_STMT)

      def bodyParse = {
        ParserUtils.rollForward(builder)
        if (ScalaTokenTypes.kYIELD.equals(builder.getTokenType)){
          ParserUtils.eatElement(builder, ScalaTokenTypes.kYIELD)
        }
        val res1 = Expr.parse(builder)
        if (res1.eq(ScalaElementTypes.EXPR)){
          rollbackMarker.drop()
          compMarker.done(ScalaElementTypes.FOR_STMT)
          ScalaElementTypes.EXPR1
        } else errorDone("Wrong expression")
      }

      def braceMatcher(brace: IElementType) = {
        val rightBrace = brace match {
          case ScalaTokenTypes.tLBRACE => ScalaTokenTypes.tRBRACE.asInstanceOf[ScalaElementType]
          case _ => ScalaTokenTypes.tRPARENTHIS.asInstanceOf[ScalaElementType]
        }
        ParserUtils.eatElement(builder, brace)
        val res = Enumerators parse(builder)
        if (res.eq(ScalaElementTypes.ENUMERATORS)){
          if (builder.getTokenType.eq (rightBrace)){
            ParserUtils.eatElement(builder, rightBrace)
            bodyParse
          } else errorDone(rightBrace.toString +" expected")
        } else errorDone("Wrong enumerators")

      }

      if (builder.getTokenType.eq(ScalaTokenTypes.kFOR)){
        ParserUtils.eatElement(builder, ScalaTokenTypes.kFOR)
        if (builder.getTokenType.eq(ScalaTokenTypes.tLPARENTHIS) ||
            builder.getTokenType.eq(ScalaTokenTypes.tLBRACE )) {
            braceMatcher(builder.getTokenType)
        } else errorDone("( of { expected")
      } else {
        rollbackMarker.rollbackTo()
        ScalaElementTypes.WRONGWAY
      }
    }

/******************** case (try) ************************/

    def tryCase: ScalaElementType = {
      val rollbackMarker = builder.mark() // marker to rollback

      /* for mistakes processing */
      def errorDone = errorDoneMain(rollbackMarker , ScalaElementTypes.FOR_STMT)

      def parseCatch = {
        var catchMarker = builder.mark()
        ParserUtils.eatElement(builder, ScalaTokenTypes.kCATCH)
        if (builder.getTokenType.equals(ScalaTokenTypes.tLBRACE)) {
          ParserUtils.eatElement(builder, ScalaTokenTypes.tLBRACE)
          /* �{� CaseClauses �}� */
          var result = CaseClauses.parse(builder)
          if (result.equals(ScalaElementTypes.CASE_CLAUSES)) {
            if (builder.getTokenType.eq(ScalaTokenTypes.tRBRACE)){
              ParserUtils.eatElement(builder, ScalaTokenTypes.tRBRACE)
            } else {
              builder.error("} expected")
            }
          } else {
            builder.error("Wrong case clauses")
          }
        } else {
          builder.error(" { expected ")
        }
        catchMarker.done(ScalaElementTypes.CATCH_BLOCK)
        ScalaElementTypes.CATCH_BLOCK
      }

      def parseFinally = {
        var finMarker = builder.mark()
        ParserUtils.eatElement(builder, ScalaTokenTypes.kFINALLY)
        var result = Expr.parse(builder)
        if (result.equals(ScalaElementTypes.WRONGWAY)) {
          builder.error("Wrong expression")
        }
        finMarker.done(ScalaElementTypes.FINALLY_BLOCK)
        ScalaElementTypes.FINALLY_BLOCK
      }

      def braceMatcher(brace: IElementType, tryMarker: PsiBuilder.Marker) = {
        val rightBrace = brace match {
          case ScalaTokenTypes.tLBRACE => ScalaTokenTypes.tRBRACE.asInstanceOf[ScalaElementType]
          case _ => ScalaTokenTypes.tRPARENTHIS.asInstanceOf[ScalaElementType]
        }
        ParserUtils.eatElement(builder, brace)
        val res = Block.parse(builder , true)
        if (res.eq(ScalaElementTypes.BLOCK)){
          if (builder.getTokenType.equals (rightBrace)){
            ParserUtils.eatElement(builder, rightBrace)
            tryMarker.done(ScalaElementTypes.TRY_BLOCK)
            if (ScalaTokenTypes.kCATCH.equals(builder.getTokenType)) parseCatch
            if (ScalaTokenTypes.kFINALLY.equals(builder.getTokenType)) parseFinally
            rollbackMarker.drop()
            compMarker.done(ScalaElementTypes.TRY_STMT)
            ScalaElementTypes.EXPR1
          } else errorDone(" " + rightBrace.toString +" expected")
        } else errorDone("Wrong enumerators")
      }

      if (builder.getTokenType.eq(ScalaTokenTypes.kTRY)){
        val tryMarker = builder.mark()
        ParserUtils.eatElement(builder, ScalaTokenTypes.kTRY)
        if (builder.getTokenType.eq(ScalaTokenTypes.tLBRACE)) {
            braceMatcher(builder.getTokenType, tryMarker)
        } else {
          tryMarker.done(ScalaElementTypes.TRY_BLOCK)
          errorDone(" { expected")
        }
      } else {
        rollbackMarker.rollbackTo()
        ScalaElementTypes.WRONGWAY
      }
    }

/*********************** case (closure) ********************/
    def closureCase: ScalaElementType = {
      val rollbackMarker = builder.mark() // marker to rollback

      def close = {
        rollbackMarker.drop()
        compMarker.done(ScalaElementTypes.METHOD_CLOSURE)
        ScalaElementTypes.EXPR1
      }

      /* for mistakes processing */
      def errorDone = errorDoneMain(rollbackMarker , ScalaElementTypes.METHOD_CLOSURE)
      if (builder.getTokenType.eq(ScalaTokenTypes.tDOT)){
        ParserUtils.eatElement(builder, ScalaTokenTypes.tDOT)
        if (builder.getTokenType.eq(ScalaTokenTypes.tIDENTIFIER)){
          ParserUtils.eatElement(builder, ScalaTokenTypes.tIDENTIFIER)
          builder.getTokenType match {
            case ScalaTokenTypes.tDOT         => {
              ParserUtils.eatElement(builder, ScalaTokenTypes.tDOT)
              if (builder.getTokenType.eq(ScalaTokenTypes.tIDENTIFIER)){
                ParserUtils.eatElement(builder, ScalaTokenTypes.tIDENTIFIER)
                close 
              } else errorDone("Identifier expected")
            }
            case  ScalaTokenTypes.tLBRACE
                 |ScalaTokenTypes.tLPARENTHIS => {
              ArgumentExprs parse builder
              close
            }
            case ScalaTokenTypes.tLSQBRACKET  => {
              TypeArgs parse builder
              close
            }
            case _ => close
          }
        } else errorDone("Identifier expected")
      } else {
        rollbackMarker.rollbackTo()
        ScalaElementTypes.WRONGWAY
      }
    }


/*********************** case (throw) **********************/

    def throwCase: ScalaElementType = {
      val rollbackMarker = builder.mark() // marker to rollback

      /* for mistakes processing */
      def errorDone = errorDoneMain(rollbackMarker , ScalaElementTypes.THROW_STMT)
      if (builder.getTokenType.eq(ScalaTokenTypes.kTHROW)){
        ParserUtils.eatElement(builder, ScalaTokenTypes.kTHROW)
        val res = Expr parse(builder)
        if (res.eq(ScalaElementTypes.EXPR)){
          rollbackMarker.drop()
          compMarker.done(ScalaElementTypes.THROW_STMT)
          ScalaElementTypes.EXPR1
        } else errorDone("Wrong expression")
      } else {
        rollbackMarker.rollbackTo()
        ScalaElementTypes.WRONGWAY
      }
    }

/********************** case (return) **************************/
    def returnCase: ScalaElementType = {
      val rollbackMarker = builder.mark() // marker to rollback
      /* for mistakes processing */
      def errorDone = errorDoneMain(rollbackMarker, ScalaElementTypes.RETURN_STMT)
      if (builder.getTokenType.eq(ScalaTokenTypes.kRETURN)){
        ParserUtils.eatElement(builder, ScalaTokenTypes.kRETURN)
        val res = Expr parse(builder)
//        if (!ScalaElementTypes.WRONGWAY.equals(res)){
        rollbackMarker.drop()
        compMarker.done(ScalaElementTypes.RETURN_STMT)
        ScalaElementTypes.EXPR1
/*
        } else {
         errorDone("Wrong expression for return statement")
        }
*/
      } else {
        rollbackMarker.rollbackTo()
        ScalaElementTypes.WRONGWAY
      }
    }

    var result = ScalaElementTypes.WRONGWAY

    /* Various variants of parsing */
    def variants(variantProcessing: => ScalaElementType) : Boolean = {
      result = variantProcessing
      !result.equals(ScalaElementTypes.WRONGWAY)
    }

    /* Parsing function body */

    /* case (throw) */
    if (variants(throwCase)) result
    /* case (return) */
    else if (variants(returnCase)) result
    /* case (if) */
    else if (variants(ifCase)) result
    /* case (try) */
    else if (variants(tryCase)) result
    /* case (for) */
    else if (variants(forCase)) result
    /* case (closure) */
    else if (variants(closureCase)) result
    /* case (while) */
    else if (variants(whileCase)) result
    /* case (do) */
    else if (variants(doCase)) result
    /* cases (b1), (b2) */
    else if (variants(bCase)) result
    /* case (a) */
    else if (variants(aCase)) result
    else {
      compMarker.rollbackTo()
      ScalaElementTypes.WRONGWAY
    }

  }

}