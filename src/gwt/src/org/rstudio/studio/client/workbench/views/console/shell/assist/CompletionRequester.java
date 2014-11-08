/*
 * CompletionRequester.java
 *
 * Copyright (C) 2009-12 by RStudio, Inc.
 *
 * Unless you have received this program directly from RStudio pursuant
 * to the terms of a commercial license agreement with RStudio, then
 * this program is licensed to you under the terms of version 3 of the
 * GNU Affero General Public License. This program is distributed WITHOUT
 * ANY EXPRESS OR IMPLIED WARRANTY, INCLUDING THOSE OF NON-INFRINGEMENT,
 * MERCHANTABILITY OR FITNESS FOR A PARTICULAR PURPOSE. Please refer to the
 * AGPL (http://www.gnu.org/licenses/agpl-3.0.txt) for more details.
 *
 */
package org.rstudio.studio.client.workbench.views.console.shell.assist;

import com.google.gwt.core.client.JsArray;
import com.google.gwt.core.client.JsArrayBoolean;
import com.google.gwt.core.client.JsArrayString;

import org.rstudio.core.client.StringUtil;
import org.rstudio.core.client.dom.DomUtils;
import org.rstudio.core.client.js.JsUtil;
import org.rstudio.studio.client.common.codetools.CodeToolsServerOperations;
import org.rstudio.studio.client.common.codetools.Completions;
import org.rstudio.studio.client.common.r.RToken;
import org.rstudio.studio.client.common.r.RTokenizer;
import org.rstudio.studio.client.server.ServerError;
import org.rstudio.studio.client.server.ServerRequestCallback;
import org.rstudio.studio.client.workbench.views.source.editors.text.AceEditor;
import org.rstudio.studio.client.workbench.views.source.editors.text.NavigableSourceEditor;
import org.rstudio.studio.client.workbench.views.source.editors.text.RFunction;
import org.rstudio.studio.client.workbench.views.source.editors.text.ScopeFunction;
import org.rstudio.studio.client.workbench.views.source.editors.text.ace.CodeModel;
import org.rstudio.studio.client.workbench.views.source.editors.text.ace.DplyrJoinContext;
import org.rstudio.studio.client.workbench.views.source.editors.text.ace.Position;
import org.rstudio.studio.client.workbench.views.source.editors.text.ace.RScopeObject;
import org.rstudio.studio.client.workbench.views.source.editors.text.ace.TokenCursor;
import org.rstudio.studio.client.workbench.views.source.model.RnwChunkOptions;
import org.rstudio.studio.client.workbench.views.source.model.RnwChunkOptions.RnwOptionCompletionResult;
import org.rstudio.studio.client.workbench.views.source.model.RnwCompletionContext;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;


public class CompletionRequester
{
   private final CodeToolsServerOperations server_ ;
   private final NavigableSourceEditor editor_ ;

   private String cachedLinePrefix_ ;
   private CompletionResult cachedResult_ ;
   private RnwCompletionContext rnwContext_ ;
   
   public CompletionRequester(CodeToolsServerOperations server,
                              RnwCompletionContext rnwContext,
                              NavigableSourceEditor editor)
   {
      server_ = server ;
      rnwContext_ = rnwContext;
      editor_ = editor;
   }
   
   private boolean usingCache(
         final String token,
         final ServerRequestCallback<CompletionResult> callback)
   {
      if (!StringUtil.isNullOrEmpty(token) &&
           cachedResult_ != null)
      {
         if (token.toLowerCase().startsWith(cachedLinePrefix_.toLowerCase()))
         {
            String diff = token.substring(cachedLinePrefix_.length(), token.length());
            if (diff.length() > 0)
            {
               ArrayList<RToken> tokens = RTokenizer.asTokens("a" + diff) ;
               
               // when we cross a :: the list may actually grow, not shrink
               if (!diff.endsWith("::"))
               {
                  while (tokens.size() > 0 
                        && tokens.get(tokens.size()-1).getContent().equals(":"))
                  {
                     tokens.remove(tokens.size()-1) ;
                  }
               
                  if (tokens.size() == 1
                        && tokens.get(0).getTokenType() == RToken.ID)
                  {
                     callback.onResponseReceived(narrow(diff)) ;
                     return true;
                  }
               }
            }
         }
      }
      
      return false;
      
   }
   
   public void getDplyrJoinCompletionsString(
         final String token,
         final String string,
         final String cursorPos,
         final boolean implicit,
         final ServerRequestCallback<CompletionResult> callback)
   {
      if (usingCache(token, callback))
         return;
      
      server_.getDplyrJoinCompletionsString(
            token,
            string,
            cursorPos,
            new ServerRequestCallback<Completions>() {
               
               @Override
               public void onResponseReceived(Completions response)
               {
                  cachedLinePrefix_ = token;
                  fillCompletionResult(response, implicit, callback);
               }

               @Override
               public void onError(ServerError error)
               {
                  callback.onError(error);
               }

            });
      
      
   }
   
   public void getDplyrJoinCompletions(
         final DplyrJoinContext joinContext,
         final boolean implicit,
         final ServerRequestCallback<CompletionResult> callback)
   {
      final String token = joinContext.getToken();
      if (usingCache(token, callback))
         return;
      
      server_.getDplyrJoinCompletions(
            joinContext.getToken(),
            joinContext.getLeftData(),
            joinContext.getRightData(),
            joinContext.getVerb(),
            joinContext.getCursorPos(),
            new ServerRequestCallback<Completions>() {

               @Override
               public void onError(ServerError error)
               {
                  callback.onError(error);
               }
               
               @Override
               public void onResponseReceived(Completions response)
               {
                  cachedLinePrefix_ = token;
                  fillCompletionResult(response, implicit, callback);
               }
               
            });
   }
   
   private void fillCompletionResult(
         Completions response,
         boolean implicit,
         ServerRequestCallback<CompletionResult> callback)
   {
      JsArrayString comp = response.getCompletions();
      JsArrayString pkgs = response.getPackages();
      JsArrayBoolean quote = response.getQuote();
      ArrayList<QualifiedName> newComp = new ArrayList<QualifiedName>();
      for (int i = 0; i < comp.length(); i++)
      {
         newComp.add(new QualifiedName(comp.get(i), pkgs.get(i), quote.get(i)));
      }

      CompletionResult result = new CompletionResult(
            response.getToken(),
            newComp,
            response.getGuessedFunctionName(),
            response.getSuggestOnAccept(),
            response.getOverrideInsertParens());

      cachedResult_ = response.isCacheable() ? result : null;

      if (!implicit || result.completions.size() != 0)
         callback.onResponseReceived(result);

   }
   
   public void getCompletions(
         final String token,
         final List<String> assocData,
         final List<Integer> dataType,
         final List<Integer> numCommas,
         final String functionCallString,
         final String chainDataName,
         final JsArrayString chainAdditionalArgs,
         final JsArrayString chainExcludeArgs,
         final boolean chainExcludeArgsFromObject,
         final boolean implicit,
         final ServerRequestCallback<CompletionResult> callback)
   {
      if (usingCache(token, callback))
         return;
      
      doGetCompletions(
            token,
            assocData,
            dataType,
            numCommas,
            functionCallString,
            chainDataName,
            chainAdditionalArgs,
            chainExcludeArgs,
            chainExcludeArgsFromObject,
            new ServerRequestCallback<Completions>()
      {
         @Override
         public void onError(ServerError error)
         {
            callback.onError(error);
         }

         @Override
         public void onResponseReceived(Completions response)
         {
            cachedLinePrefix_ = token;
            String token = response.getToken();

            JsArrayString comp = response.getCompletions();
            JsArrayString pkgs = response.getPackages();
            JsArrayBoolean quote = response.getQuote();
            ArrayList<QualifiedName> newComp = new ArrayList<QualifiedName>();
            
            // Try getting our own function argument completions
            if (!response.getExcludeOtherCompletions())
            {
               addFunctionArgumentCompletions(token, newComp);
               addScopedArgumentCompletions(token, newComp);
            }
            
            // Get server completions
            for (int i = 0; i < comp.length(); i++)
            {
               newComp.add(new QualifiedName(comp.get(i), pkgs.get(i), quote.get(i)));
            }
            
            // Get variable completions from the current scope
            if (!response.getExcludeOtherCompletions())
            {
               addScopedCompletions(token, newComp, "variable");
               addScopedCompletions(token, newComp, "function");
            }
            
            CompletionResult result = new CompletionResult(
                  response.getToken(),
                  newComp,
                  response.getGuessedFunctionName(),
                  response.getSuggestOnAccept(),
                  response.getOverrideInsertParens());

            cachedResult_ = response.isCacheable() ? result : null;

            if (!implicit || result.completions.size() != 0)
               callback.onResponseReceived(result);
         }
      }) ;
   }
   
   @SuppressWarnings("unused")
   private ArrayList<QualifiedName> withoutDupes(ArrayList<QualifiedName> completions)
   {
      Set<String> names = new HashSet<String>();
      
      ArrayList<QualifiedName> noDupes = new ArrayList<QualifiedName>();
      for (int i = 0; i < completions.size(); i++)
      {
         if (!names.contains(completions.get(i).name))
         {
            noDupes.add(completions.get(i));
            names.add(completions.get(i).name);
         }
      }
      return noDupes;
   }
   
   private void addScopedArgumentCompletions(
         String token,
         ArrayList<QualifiedName> completions)
   {
      AceEditor editor = (AceEditor) editor_;

      // NOTE: this will be null in the console, so protect against that
      if (editor != null)
      {
         Position cursorPosition =
               editor.getSession().getSelection().getCursor();
         CodeModel codeModel = editor.getSession().getMode().getCodeModel();
         JsArray<RFunction> scopedFunctions =
               codeModel.getFunctionsInScope(cursorPosition);

         for (int i = 0; i < scopedFunctions.length(); i++)
         {
            RFunction scopedFunction = scopedFunctions.get(i);
            String functionName = scopedFunction.getFunctionName();

            JsArrayString argNames = scopedFunction.getFunctionArgs();
            for (int j = 0; j < argNames.length(); j++)
            {
               String argName = argNames.get(j);
               if (argName.startsWith(token))
               {
                  if (functionName == null || functionName == "")
                  {
                     completions.add(new QualifiedName(
                           argName,
                           "<anonymous function>"
                     ));
                  }
                  else
                  {
                     completions.add(new QualifiedName(
                           argName,
                           "[" + functionName + "]"
                     ));
                  }
               }
            }
         } 
      }
   }
      
   private void addScopedCompletions(
         String token,
         ArrayList<QualifiedName> completions,
         String type)
   {
      AceEditor editor = (AceEditor) editor_;

      // NOTE: this will be null in the console, so protect against that
      if (editor != null)
      {
         Position cursorPosition =
               editor.getSession().getSelection().getCursor();
         CodeModel codeModel = editor.getSession().getMode().getCodeModel();
      
         JsArray<RScopeObject> scopeVariables = codeModel.getVariablesInScope(cursorPosition);
         for (int i = 0; i < scopeVariables.length(); i++)
         {
            RScopeObject variable = scopeVariables.get(i);
            if (variable.getToken().startsWith(token) && variable.getType() == type)
               completions.add(new QualifiedName(
                     variable.getToken(),
                     "<" + variable.getType() + ">"
               ));
         }
      }
   }
   
   private void addFunctionArgumentCompletions(
         String token,
         ArrayList<QualifiedName> completions)
   {
      AceEditor editor = (AceEditor) editor_;

      if (editor != null)
      {
         Position cursorPosition =
               editor.getSession().getSelection().getCursor();
         CodeModel codeModel = editor.getSession().getMode().getCodeModel();
         
         // Try to see if we can find a function name
         TokenCursor cursor = codeModel.getTokenCursor();
         
         // NOTE: This can fail if the document is empty
         if (!cursor.moveToPosition(cursorPosition))
            return;
         
         if (cursor.currentValue() == "(" || cursor.findOpeningBracket("(", false))
         {
            if (cursor.moveToPreviousToken())
            {
               // Check to see if this really is the name of a function
               JsArray<ScopeFunction> functionsInScope =
                     codeModel.getAllFunctionScopes();
               
               String tokenName = cursor.currentValue();
               for (int i = 0; i < functionsInScope.length(); i++)
               {
                  ScopeFunction rFunction = functionsInScope.get(i);
                  String fnName = rFunction.getFunctionName();
                  if (tokenName == fnName)
                  {
                     JsArrayString args = rFunction.getFunctionArgs();
                     for (int j = 0; j < args.length(); j++)
                     {
                        completions.add(new QualifiedName(
                              args.get(j) + " = ",
                              "[" + fnName + "]"
                        ));
                     }
                  }
               }
            }
         }
      }
   }

   private void doGetCompletions(
         final String token,
         final List<String> assocData,
         final List<Integer> dataType,
         final List<Integer> numCommas,
         final String functionCallString,
         final String chainObjectName,
         final JsArrayString chainAdditionalArgs,
         final JsArrayString chainExcludeArgs,
         final boolean chainExcludeArgsFromObject,
         final ServerRequestCallback<Completions> requestCallback)
   {
      int optionsStartOffset;
      if (rnwContext_ != null &&
          (optionsStartOffset = rnwContext_.getRnwOptionsStart(token, token.length())) >= 0)
      {
         doGetSweaveCompletions(token, optionsStartOffset, token.length(), requestCallback);
      }
      else
      {
         server_.getCompletions(
               token,
               assocData,
               dataType,
               numCommas,
               functionCallString,
               chainObjectName,
               chainAdditionalArgs,
               chainExcludeArgs,
               chainExcludeArgsFromObject,
               requestCallback);
      }
   }

   private void doGetSweaveCompletions(
         final String line,
         final int optionsStartOffset,
         final int cursorPos,
         final ServerRequestCallback<Completions> requestCallback)
   {
      rnwContext_.getChunkOptions(new ServerRequestCallback<RnwChunkOptions>()
      {
         @Override
         public void onResponseReceived(RnwChunkOptions options)
         {
            RnwOptionCompletionResult result = options.getCompletions(
                  line,
                  optionsStartOffset,
                  cursorPos,
                  rnwContext_ == null ? null : rnwContext_.getActiveRnwWeave());
            
            String[] pkgNames = new String[result.completions.length()];
            Arrays.fill(pkgNames, "`chunk-option`");

            Completions response = Completions.createCompletions(
                  result.token,
                  result.completions,
                  JsUtil.toJsArrayString(pkgNames),
                  JsUtil.toJsArrayBoolean(new ArrayList<Boolean>(result.completions.length())),
                  "",
                  false,
                  false);
            
            // Unlike other completion types, Sweave completions are not
            // guaranteed to narrow the candidate list (in particular
            // true/false).
            response.setCacheable(false);
            if (result.completions.length() > 0 &&
                result.completions.get(0).endsWith("="))
            {
               response.setSuggestOnAccept(true);
            }
            requestCallback.onResponseReceived(response);
         }

         @Override
         public void onError(ServerError error)
         {
            requestCallback.onError(error);
         }
      });
   }

   public void flushCache()
   {
      cachedLinePrefix_ = null ;
      cachedResult_ = null ;
   }
   
   private CompletionResult narrow(String diff)
   {
      String token = cachedResult_.token.toLowerCase() + diff ;
      ArrayList<QualifiedName> newCompletions = new ArrayList<QualifiedName>() ;
      for (QualifiedName qname : cachedResult_.completions)
         if (qname.name.toLowerCase().startsWith(token.toLowerCase()))
            newCompletions.add(qname) ;
      
      return new CompletionResult(
            token,
            newCompletions,
            null,
            cachedResult_.suggestOnAccept,
            cachedResult_.dontInsertParens) ;
   }

   public static class CompletionResult
   {
      public CompletionResult(String token,
                              ArrayList<QualifiedName> completions,
                              String guessedFunctionName,
                              boolean suggestOnAccept,
                              boolean dontInsertParens)
      {
         this.token = token ;
         this.completions = completions ;
         this.guessedFunctionName = guessedFunctionName ;
         this.suggestOnAccept = suggestOnAccept ;
         this.dontInsertParens = dontInsertParens ;
      }
      
      public final String token ;
      public final ArrayList<QualifiedName> completions ;
      public final String guessedFunctionName ;
      public final boolean suggestOnAccept ;
      public final boolean dontInsertParens ;
   }
   
   public static class QualifiedName implements Comparable<QualifiedName>
   {
      public QualifiedName(String name, String pkgName, boolean shouldQuote)
      {
         this.name = name;
         this.pkgName = pkgName;
         this.shouldQuote = shouldQuote;
      }
      
      public QualifiedName(String name, String pkgName)
      {
         this.name = name;
         this.pkgName = pkgName;
         this.shouldQuote = false;
      }
      
      @Override
      public String toString()
      {
         return DomUtils.textToHtml(name) + getFormattedPackageName();
      }

      private String getFormattedPackageName()
      {
         if (pkgName == null || pkgName.length() == 0)
            return "";
         
         StringBuilder result = new StringBuilder();
         result.append(" <span class=\"packageName\">");
         if (pkgName.matches("^([\\{\\[\\(\\<`'\"]).*\\1$"))
            result.append(DomUtils.textToHtml(pkgName));
         else
            result.append("{").append(DomUtils.textToHtml(pkgName)).append("}");
         result.append("</span>");
         return result.toString();
      }

      public static QualifiedName parseFromText(String val)
      {
         String name, pkgName = "";
         int idx = val.indexOf('{') ;
         if (idx < 0)
         {
            name = val ;
         }
         else
         {
            name = val.substring(0, idx).trim() ;
            pkgName = val.substring(idx + 1, val.length() - 1) ;
         }
         
         return new QualifiedName(name, pkgName) ;
      }

      public int compareTo(QualifiedName o)
      {
         if (name.endsWith("=") ^ o.name.endsWith("="))
            return name.endsWith("=") ? -1 : 1 ;
         
         int result = String.CASE_INSENSITIVE_ORDER.compare(name, o.name) ;
         if (result != 0)
            return result ;
         
         String pkg = pkgName == null ? "" : pkgName ;
         String opkg = o.pkgName == null ? "" : o.pkgName ;
         return pkg.compareTo(opkg) ;
      }

      public final String name ;
      public final String pkgName ;
      public final boolean shouldQuote ;
   }
}
