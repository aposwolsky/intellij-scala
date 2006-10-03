package org.jetbrains.plugins.scala.highlighter;

import com.intellij.lang.PairedBraceMatcher;
import com.intellij.lang.BracePair;
import tests.lexer.ScalaTokenTypes;

/**
 * Author: Ilya Sergey
 * Date: 29.09.2006
 * Time: 20:26:52
 */
public class ScalaBraceMatcher implements PairedBraceMatcher {

    private static final BracePair[] PAIRS = new BracePair[] {
       new BracePair('(', ScalaTokenTypes.tLPARENTHIS, ')', ScalaTokenTypes.tRPARENTHIS, false), 
       new BracePair('[',ScalaTokenTypes.tLSQBRACKET, ']', ScalaTokenTypes.tRSQBRACKET, false),
       new BracePair('{',ScalaTokenTypes.tLBRACE, '}', ScalaTokenTypes.tRBRACE, true)
     };

    public BracePair[] getPairs() {
        return PAIRS; 
    }
}
