/*
 * SonarQube Java
 * Copyright (C) 2012-2016 SonarSource SA
 * mailto:contact AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.java.filters;

import com.google.common.collect.ContiguousSet;
import com.google.common.collect.DiscreteDomain;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.collect.Range;

import org.sonar.api.issue.Issue;
import org.sonar.api.utils.AnnotationUtils;
import org.sonar.check.Rule;
import org.sonar.java.syntaxtoken.FirstSyntaxTokenFinder;
import org.sonar.java.syntaxtoken.LastSyntaxTokenFinder;
import org.sonar.plugins.java.api.JavaCheck;
import org.sonar.plugins.java.api.JavaFileScannerContext;
import org.sonar.plugins.java.api.tree.BaseTreeVisitor;
import org.sonar.plugins.java.api.tree.SyntaxToken;
import org.sonar.plugins.java.api.tree.Tree;

import java.util.Map;
import java.util.Set;

public abstract class BaseTreeVisitorIssueFilter extends BaseTreeVisitor implements JavaIssueFilter {

  private String componentKey;
  private final Multimap<String, Integer> excludedLinesByRule;
  private final Map<Class<? extends JavaCheck>, String> rulesKeysByRulesClass;

  public BaseTreeVisitorIssueFilter() {
    excludedLinesByRule = HashMultimap.create();
    rulesKeysByRulesClass = rulesKeysByRulesClass(filteredRules());
  }

  private static Map<Class<? extends JavaCheck>, String> rulesKeysByRulesClass(Set<Class<? extends JavaCheck>> rules) {
    Map<Class<? extends JavaCheck>, String> results = Maps.newHashMap();
    for (Class<? extends JavaCheck> ruleClass : rules) {
      Rule ruleAnnotation = AnnotationUtils.getAnnotation(ruleClass, Rule.class);
      if (ruleAnnotation != null) {
        results.put(ruleClass, ruleAnnotation.key());
      }
    }
    return results;
  }

  @Override
  public void setComponentKey(String componentKey) {
    this.componentKey = componentKey;
  }

  public String getComponentKey() {
    return componentKey;
  }

  @Override
  public void scanFile(JavaFileScannerContext context) {
    excludedLinesByRule.clear();
    scan(context.getTree());
  }

  @Override
  public boolean accept(Issue issue) {
    return !(issue.componentKey().equals(componentKey) && excludedLinesByRule.get(issue.ruleKey().rule()).contains(issue.line()));
  }

  public Multimap<String, Integer> excludedLinesByRule() {
    return excludedLinesByRule;
  }

  public void acceptLines(Tree tree, Iterable<Class<? extends JavaCheck>> rules) {
    for (Class<? extends JavaCheck> rule : rules) {
      acceptLines(tree, rule);
    }
  }

  public void acceptLines(Tree tree, Class<? extends JavaCheck> rule) {
    computeFilteredLinesForRule(tree, rule, false);
  }

  public void excludeLines(Tree tree, Iterable<Class<? extends JavaCheck>> rules) {
    for (Class<? extends JavaCheck> rule : rules) {
      excludeLines(tree, rule);
    }
  }

  public void excludeLines(Set<Integer> lines, String ruleKey) {
    computeFilteredLinesForRule(lines, ruleKey, true);
  }

  public void excludeLines(Tree tree, Class<? extends JavaCheck> rule) {
    computeFilteredLinesForRule(tree, rule, true);
  }

  private void computeFilteredLinesForRule(Tree tree, Class<? extends JavaCheck> filteredRule, boolean excludeLine) {
    SyntaxToken firstSyntaxToken = FirstSyntaxTokenFinder.firstSyntaxToken(tree);
    SyntaxToken lastSyntaxToken = LastSyntaxTokenFinder.lastSyntaxToken(tree);
    if (firstSyntaxToken != null && lastSyntaxToken != null) {
      Set<Integer> filteredlines = ContiguousSet.create(Range.closed(firstSyntaxToken.line(), lastSyntaxToken.line()), DiscreteDomain.integers());
      computeFilteredLinesForRule(filteredlines, rulesKeysByRulesClass.get(filteredRule), excludeLine);
    }
  }

  private void computeFilteredLinesForRule(Set<Integer> lines, String ruleKey, boolean excludeLine) {
    if (excludeLine) {
      excludedLinesByRule.putAll(ruleKey, lines);
    } else {
      excludedLinesByRule.get(ruleKey).removeAll(lines);
    }
  }
}
