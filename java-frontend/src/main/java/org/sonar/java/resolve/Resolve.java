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
package org.sonar.java.resolve;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Sets;

import org.sonar.java.model.expression.ConditionalExpressionTreeImpl;
import org.sonar.plugins.java.api.semantic.Symbol;
import org.sonar.plugins.java.api.semantic.Type;
import org.sonar.plugins.java.api.tree.ConditionalExpressionTree;
import org.sonar.plugins.java.api.tree.LambdaExpressionTree;
import org.sonar.plugins.java.api.tree.Tree;

import javax.annotation.CheckForNull;
import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Routines for name resolution.
 * <p/>
 * Lookup by name and then filter by type is performant, because amount of symbols with same name are relatively small.
 * <p/>
 * Naming conventions:
 * env - is the environment where the symbol was mentioned
 * site - is the type of which symbol is a member
 * name - is the symbol's name
 * <p/>
 * TODO site should be represented by class Type
 */
public class Resolve {

  private final JavaSymbolNotFound symbolNotFound = new JavaSymbolNotFound();

  private final BytecodeCompleter bytecodeCompleter;
  private final TypeSubstitutionSolver typeSubstitutionSolver;
  private final Types types = new Types();
  private final Symbols symbols;

  public Resolve(Symbols symbols, BytecodeCompleter bytecodeCompleter, ParametrizedTypeCache parametrizedTypeCache) {
    this.symbols = symbols;
    this.bytecodeCompleter = bytecodeCompleter;
    this.typeSubstitutionSolver = new TypeSubstitutionSolver(parametrizedTypeCache, symbols);
  }

  @Nullable
  private static JavaSymbol.TypeJavaSymbol superclassSymbol(JavaSymbol.TypeJavaSymbol c) {
    JavaType supertype = c.getSuperclass();
    return supertype == null ? null : supertype.symbol;
  }

  public JavaSymbol.TypeJavaSymbol registerClass(JavaSymbol.TypeJavaSymbol classSymbol) {
    return bytecodeCompleter.registerClass(classSymbol);
  }

  public Scope createStarImportScope(JavaSymbol owner) {
    return new Scope.StarImportScope(owner, bytecodeCompleter);
  }

  public Scope createStaticStarImportScope(JavaSymbol owner) {
    return new Scope.StaticStarImportScope(owner, bytecodeCompleter);
  }

  public JavaType resolveTypeSubstitution(JavaType type, JavaType definition) {
    return typeSubstitutionSolver.applySiteSubstitution(type, definition);
  }

  public JavaType resolveTypeSubstitutionWithDiamondOperator(ParametrizedTypeJavaType type, JavaType definition) {
    ParametrizedTypeJavaType result = type;
    if (definition.isParameterized()) {
      TypeSubstitution substitution = TypeSubstitutionSolver.substitutionFromSuperType(type, (ParametrizedTypeJavaType) definition);
      result = (ParametrizedTypeJavaType) typeSubstitutionSolver.applySubstitution(type, substitution);
    }
    return typeSubstitutionSolver.erasureSubstitution(result);
  }

  public JavaType parametrizedTypeWithErasure(ParametrizedTypeJavaType type) {
    return typeSubstitutionSolver.erasureSubstitution(type);
  }

  /**
   * Finds field with given name.
   */
  private Resolution findField(Env env, JavaSymbol.TypeJavaSymbol site, String name, JavaSymbol.TypeJavaSymbol c) {
    Resolution bestSoFar = unresolved();
    Resolution resolution = new Resolution();
    for (JavaSymbol symbol : c.members().lookup(name)) {
      if (symbol.kind == JavaSymbol.VAR) {
        if(isAccessible(env, site, symbol)) {
          resolution.symbol = symbol;
          resolution.type = typeSubstitutionSolver.applySiteSubstitution(symbol.type, c.type);
          return resolution;
        } else {
          return Resolution.resolution(new AccessErrorJavaSymbol(symbol, Symbols.unknownType));
        }
      }
    }
    if (c.getSuperclass() != null) {
      resolution = findField(env, site, name, c.getSuperclass().symbol);
      if (resolution.symbol.kind < bestSoFar.symbol.kind) {
        resolution.type = typeSubstitutionSolver.applySiteSubstitution(resolution.symbol.type, c.getSuperclass());
        bestSoFar = resolution;
      }
    }
    for (JavaType interfaceType : c.getInterfaces()) {
      resolution = findField(env, site, name, interfaceType.symbol);
      if (resolution.symbol.kind < bestSoFar.symbol.kind) {
        bestSoFar = resolution;
      }
    }
    return bestSoFar;
  }

  /**
   * Finds variable or field with given name.
   */
  private Resolution findVar(Env env, String name) {
    Resolution bestSoFar = unresolved();

    Env env1 = env;
    while (env1.outer != null) {
      Resolution sym = new Resolution();
      for (JavaSymbol symbol : env1.scope.lookup(name)) {
        if (symbol.kind == JavaSymbol.VAR) {
          sym.symbol = symbol;
        }
      }
      if (sym.symbol == null) {
        sym = findField(env1, env1.enclosingClass, name, env1.enclosingClass);
      }
      if (sym.symbol.kind < JavaSymbol.ERRONEOUS) {
        // symbol exists
        return sym;
      } else if (sym.symbol.kind < bestSoFar.symbol.kind) {
        bestSoFar = sym;
      }
      env1 = env1.outer;
    }

    JavaSymbol symbol = findVarInStaticImport(env, name);
    if (symbol.kind < JavaSymbol.ERRONEOUS) {
      // symbol exists
      return Resolution.resolution(symbol);
    } else if (symbol.kind < bestSoFar.symbol.kind) {
      bestSoFar = Resolution.resolution(symbol);
    }
    return bestSoFar;
  }

  private JavaSymbol findVarInStaticImport(Env env, String name) {
    JavaSymbol bestSoFar = symbolNotFound;
    for (JavaSymbol symbol : env.namedImports.lookup(name)) {
      if ((JavaSymbol.VAR & symbol.kind) != 0) {
        return symbol;
      }
    }
    for (JavaSymbol symbol : env.staticStarImports.lookup(name)) {
      if ((JavaSymbol.VAR & symbol.kind) != 0) {
        return symbol;
      }
    }
    return bestSoFar;
  }

  private JavaSymbol findMemberType(Env env, JavaSymbol.TypeJavaSymbol site, String name, JavaSymbol.TypeJavaSymbol c) {
    JavaSymbol bestSoFar = symbolNotFound;
    for (JavaSymbol symbol : c.members().lookup(name)) {
      if (symbol.kind == JavaSymbol.TYP) {
        return isAccessible(env, site, symbol)
            ? symbol
            : new AccessErrorJavaSymbol(symbol, Symbols.unknownType);
      }
    }
    if (c.getSuperclass() != null) {
      JavaSymbol symbol = findMemberType(env, site, name, c.getSuperclass().symbol);
      if (symbol.kind < bestSoFar.kind) {
        bestSoFar = symbol;
      }
    }
    if (c.getInterfaces() == null) {
      // Invariant to check that interfaces are not set only when we are looking into the symbol we are currently completing.
      // required for generics
      Preconditions.checkState(c.completing, "interfaces of a symbol not currently completing are not set.");
      Preconditions.checkState(c == site);
    } else {
      for (JavaType interfaceType : c.getInterfaces()) {
        JavaSymbol symbol = findMemberType(env, site, name, interfaceType.symbol);
        if (symbol.kind < bestSoFar.kind) {
          bestSoFar = symbol;
        }
      }
    }
    return bestSoFar;
  }

  /**
   * Finds type with given name.
   */
  private JavaSymbol findType(Env env, String name) {
    JavaSymbol bestSoFar = symbolNotFound;
    for (Env env1 = env; env1 != null; env1 = env1.outer) {
      for (JavaSymbol symbol : env1.scope.lookup(name)) {
        if (symbol.kind == JavaSymbol.TYP) {
          return symbol;
        }
      }
      if (env1.outer != null) {
        JavaSymbol symbol = findMemberType(env1, env1.enclosingClass, name, env1.enclosingClass);
        if (symbol.kind < JavaSymbol.ERRONEOUS) {
          // symbol exists
          return symbol;
        } else if (symbol.kind < bestSoFar.kind) {
          bestSoFar = symbol;
        }
      }
    }

    //checks predefined types
    JavaSymbol predefinedSymbol = findMemberType(env, symbols.predefClass, name, symbols.predefClass);
    if (predefinedSymbol.kind < bestSoFar.kind) {
      return predefinedSymbol;
    }

    //JLS8 6.4.1 Shadowing rules
    //named imports
    for (JavaSymbol symbol : env.namedImports.lookup(name)) {
      if (symbol.kind == JavaSymbol.TYP) {
        return symbol;
      }
    }
    //package types
    JavaSymbol sym = findIdentInPackage(env.packge, name, JavaSymbol.TYP);
    if (sym.kind < bestSoFar.kind) {
      return sym;
    }
    //on demand imports
    for (JavaSymbol symbol : env.starImports.lookup(name)) {
      if (symbol.kind == JavaSymbol.TYP) {
        return symbol;
      }
    }
    //java.lang
    JavaSymbol.PackageJavaSymbol javaLang = bytecodeCompleter.enterPackage("java.lang");
    for (JavaSymbol symbol : javaLang.completedMembers().lookup(name)) {
      if (symbol.kind == JavaSymbol.TYP) {
        return symbol;
      }
    }
    return bestSoFar;
  }

  /**
   * @param kind subset of {@link JavaSymbol#VAR}, {@link JavaSymbol#TYP}, {@link JavaSymbol#PCK}
   */
  public Resolution findIdent(Env env, String name, int kind) {
    Resolution bestSoFar = unresolved();
    if ((kind & JavaSymbol.VAR) != 0) {
      Resolution res = findVar(env, name);
      if (res.symbol.kind < JavaSymbol.ERRONEOUS) {
        // symbol exists
        return res;
      } else if (res.symbol.kind < bestSoFar.symbol.kind) {
        bestSoFar = res;
      }
    }
    if ((kind & JavaSymbol.TYP) != 0) {
      Resolution res = new Resolution();
      res.symbol = findType(env, name);
      if (res.symbol.kind < JavaSymbol.ERRONEOUS) {
        // symbol exists
        return res;
      } else if (res.symbol.kind < bestSoFar.symbol.kind) {
        bestSoFar = res;
      }
    }
    if ((kind & JavaSymbol.PCK) != 0) {
      Resolution res = new Resolution();
      res.symbol = findIdentInPackage(symbols.defaultPackage, name, JavaSymbol.PCK);
      if (res.symbol.kind < JavaSymbol.ERRONEOUS) {
        // symbol exists
        return res;
      } else if (res.symbol.kind < bestSoFar.symbol.kind) {
        bestSoFar = res;
      }
    }
    return bestSoFar;
  }

  /**
   * @param kind subset of {@link JavaSymbol#TYP}, {@link JavaSymbol#PCK}
   */
  public JavaSymbol findIdentInPackage(JavaSymbol site, String name, int kind) {
    String fullname = bytecodeCompleter.formFullName(name, site);
    JavaSymbol bestSoFar = symbolNotFound;
    //Try to find a type matching the name.
    if ((kind & JavaSymbol.TYP) != 0) {
      JavaSymbol sym = bytecodeCompleter.loadClass(fullname);
      if (sym.kind < bestSoFar.kind) {
        bestSoFar = sym;
      }
    }
    //We did not find the class so identifier must be a package.
    if ((kind & JavaSymbol.PCK) != 0 && bestSoFar.kind >= symbolNotFound.kind) {
      bestSoFar = bytecodeCompleter.enterPackage(fullname);
    }
    return bestSoFar;
  }

  /**
   * @param kind subset of {@link JavaSymbol#VAR}, {@link JavaSymbol#TYP}
   */
  public Resolution findIdentInType(Env env, JavaSymbol.TypeJavaSymbol site, String name, int kind) {
    Resolution bestSoFar = unresolved();
    Resolution resolution;
    JavaSymbol symbol;
    if ((kind & JavaSymbol.VAR) != 0) {
      resolution = findField(env, site, name, site);
      if (resolution.symbol.kind < JavaSymbol.ERRONEOUS) {
        // symbol exists
        return resolution;
      } else if (resolution.symbol.kind < bestSoFar.symbol.kind) {
        bestSoFar = resolution;
      }
    }
    if ((kind & JavaSymbol.TYP) != 0) {
      symbol = findMemberType(env, site, name, site);
      if (symbol.kind < JavaSymbol.ERRONEOUS) {
        // symbol exists
        return Resolution.resolution(symbol);
      } else if (symbol.kind < bestSoFar.symbol.kind) {
        bestSoFar = Resolution.resolution(symbol);
      }
    }
    return bestSoFar;
  }

  /**
   * Finds method matching given name and types of arguments.
   */
  public Resolution findMethod(Env env, String name, List<JavaType> argTypes, List<JavaType> typeParamTypes) {
    Resolution bestSoFar = unresolved();
    Env env1 = env;
    while (env1.outer != null) {
      Resolution res = findMethod(env1, env1.enclosingClass.getType(), name, argTypes, typeParamTypes);
      if (res.symbol.kind < JavaSymbol.ERRONEOUS) {
        // symbol exists
        return res;
      } else if (res.symbol.kind < bestSoFar.symbol.kind) {
        bestSoFar = res;
      }
      env1 = env1.outer;
    }
    Resolution res = findMethodInStaticImport(env, name, argTypes, typeParamTypes);
    if (res.symbol.kind < JavaSymbol.ERRONEOUS) {
      // symbol exists
      return res;
    } else if (res.symbol.kind < bestSoFar.symbol.kind) {
      bestSoFar = res;
    }
    return bestSoFar;
  }

  private Resolution findMethodInStaticImport(Env env, String name, List<JavaType> argTypes, List<JavaType> typeParamTypes) {
    Resolution bestSoFar = unresolved();
    JavaType enclosingType = env.enclosingClass.getType();
    bestSoFar = lookupInScope(env, enclosingType, enclosingType, name, argTypes, typeParamTypes, false, env.namedImports, bestSoFar);
    if (bestSoFar.symbol.kind < JavaSymbol.ERRONEOUS) {
      // symbol exists
      return bestSoFar;
    }
    bestSoFar = lookupInScope(env, enclosingType, enclosingType, name, argTypes, typeParamTypes, false, env.staticStarImports, bestSoFar);
    if (bestSoFar.symbol.kind < JavaSymbol.ERRONEOUS) {
      // symbol exists
      return bestSoFar;
    }
    bestSoFar = lookupInScope(env, enclosingType, enclosingType, name, argTypes, typeParamTypes, true, env.namedImports, bestSoFar);
    if (bestSoFar.symbol.kind < JavaSymbol.ERRONEOUS) {
      // symbol exists
      return bestSoFar;
    }
    bestSoFar = lookupInScope(env, enclosingType, enclosingType, name, argTypes, typeParamTypes, true, env.staticStarImports, bestSoFar);
    return bestSoFar;
  }

  public Resolution findMethod(Env env, JavaType site, String name, List<JavaType> argTypes) {
    return findMethod(env, site, site, name, argTypes, ImmutableList.<JavaType>of(), false);
  }

  public Resolution findMethod(Env env, JavaType site, String name, List<JavaType> argTypes, List<JavaType> typeParams) {
    return findMethod(env, site, site, name, argTypes, typeParams, false);
  }

  private Resolution findMethod(Env env, JavaType callSite, JavaType site, String name, List<JavaType> argTypes, List<JavaType> typeParams) {
    return findMethod(env, callSite, site, name, argTypes, typeParams, false);
  }

  private Resolution findConstructor(Env env, JavaType site, List<JavaType> argTypes, List<JavaType> typeParams, boolean autoboxing) {
    List<JavaType> newArgTypes = argTypes;
    JavaSymbol owner = site.symbol.owner();
    if (!owner.isPackageSymbol() && !site.symbol.isStatic()) {
      // JLS8 - 8.8.1 & 8.8.9 : constructors of inner class have an implicit first arg of its directly enclosing class type
      newArgTypes = ImmutableList.<JavaType>builder().add(owner.enclosingClass().type).addAll(argTypes).build();
    }
    return findMethod(env, site, site, "<init>", newArgTypes, typeParams, autoboxing);
  }

  private Resolution findMethod(Env env, JavaType callSite, JavaType site, String name, List<JavaType> argTypes, List<JavaType> typeParams, boolean autoboxing) {
    JavaType superclass = site.getSymbol().getSuperclass();
    Resolution bestSoFar = unresolved();
    // handle constructors
    if ("this".equals(name)) {
      return findConstructor(env, site, argTypes, typeParams, autoboxing);
    } else if ("super".equals(name)) {
      if (superclass == null) {
        return bestSoFar;
      }
      return findConstructor(env, superclass, argTypes, typeParams, autoboxing);
    }
    bestSoFar = lookupInScope(env, callSite, site, name, argTypes, typeParams, autoboxing, site.getSymbol().members(), bestSoFar);

    //look in supertypes for more specialized method (overloading).
    if (superclass != null) {
      Resolution method = findMethod(env, callSite, superclass, name, argTypes, typeParams);
      method.type = typeSubstitutionSolver.applySiteSubstitution(method.type, site, superclass);
      Resolution best = selectBest(env, superclass, callSite, argTypes, typeParams, method.symbol, bestSoFar, autoboxing);
      if (best.symbol == method.symbol) {
        bestSoFar = method;
      }
    }
    for (JavaType interfaceType : site.getSymbol().getInterfaces()) {
      Resolution method = findMethod(env, callSite, interfaceType, name, argTypes, typeParams);
      method.type = typeSubstitutionSolver.applySiteSubstitution(method.type, site, interfaceType);
      Resolution best = selectBest(env, interfaceType, callSite, argTypes, typeParams, method.symbol, bestSoFar, autoboxing);
      if (best.symbol == method.symbol) {
        bestSoFar = method;
      }
    }
    if(bestSoFar.symbol.kind >= JavaSymbol.ERRONEOUS && !autoboxing) {
      bestSoFar = findMethod(env, callSite, site, name, argTypes, typeParams, true);
    }
    return bestSoFar;
  }

  private Resolution lookupInScope(Env env, JavaType callSite, JavaType site, String name, List<JavaType> argTypes, List<JavaType> typeParams,
                                   boolean autoboxing, Scope scope, Resolution bestFound) {
    Resolution bestSoFar = bestFound;
    // look in site members
    for (JavaSymbol symbol : scope.lookup(name)) {
      if (symbol.kind == JavaSymbol.MTH) {
        Resolution best = selectBest(env, site, callSite, argTypes, typeParams, symbol, bestSoFar, autoboxing);
        if (best.symbol == symbol) {
          bestSoFar = best;
        }
      }
    }
    return bestSoFar;
  }

  /**
   * @param candidate    candidate
   * @param bestSoFar previously found best match
   */
  private Resolution selectBest(Env env, JavaType defSite, JavaType callSite, List<JavaType> argTypes, List<JavaType> typeParams,
                                JavaSymbol candidate, Resolution bestSoFar, boolean autoboxing) {
    JavaSymbol.TypeJavaSymbol siteSymbol = callSite.symbol;
    // TODO get rid of null check
    if (candidate.kind >= JavaSymbol.ERRONEOUS || !isInheritedIn(candidate, siteSymbol) || candidate.type == null) {
      return bestSoFar;
    }
    JavaSymbol.MethodJavaSymbol methodJavaSymbol = (JavaSymbol.MethodJavaSymbol) candidate;
    if(!hasCompatibleArity(methodJavaSymbol.parameterTypes().size(), argTypes.size(), methodJavaSymbol.isVarArgs())) {
      return bestSoFar;
    }
    TypeSubstitution substitution = typeSubstitutionSolver.getTypeSubstitution(methodJavaSymbol, callSite, typeParams, argTypes);
    List<JavaType> formals = ((MethodJavaType) methodJavaSymbol.type).argTypes;
    formals = typeSubstitutionSolver.applySiteSubstitutionToFormalParameters(formals, callSite);
    if(defSite != callSite) {
      formals = typeSubstitutionSolver.applySiteSubstitutionToFormalParameters(formals, defSite);
    }
    formals = typeSubstitutionSolver.applySubstitutionToFormalParameters(formals, substitution);
    if (!isArgumentsAcceptable(argTypes, formals, methodJavaSymbol.isVarArgs(), autoboxing)) {
      return bestSoFar;
    }
    // TODO ambiguity, errors, ...
    if (!isAccessible(env, siteSymbol, candidate)) {
      Resolution resolution = new Resolution(new AccessErrorJavaSymbol(candidate, Symbols.unknownType));
      resolution.type = Symbols.unknownType;
      return resolution;
    }
    JavaSymbol mostSpecific = selectMostSpecific(candidate, bestSoFar.symbol, argTypes, substitution);
    if (mostSpecific.isKind(JavaSymbol.AMBIGUOUS)) {
      // same signature, we keep the first symbol found (overrides the other one).
      return bestSoFar;
    }

    Resolution resolution = new Resolution(mostSpecific);
    JavaSymbol.MethodJavaSymbol mostSpecificMethod = (JavaSymbol.MethodJavaSymbol) mostSpecific;
    List<JavaType> thrownTypes = ((MethodJavaType) mostSpecific.type).thrown;
    JavaType returnType = ((MethodJavaType) mostSpecificMethod.type).resultType;
    if(applicableWithUncheckedConversion(mostSpecificMethod, callSite, typeParams) && !mostSpecificMethod.isConstructor()) {
      returnType = returnType.erasure();
      thrownTypes = erasure(thrownTypes);
    } else {
      returnType = typeSubstitutionSolver.getReturnType(returnType, defSite, callSite, substitution, mostSpecificMethod);
    }
    resolution.type = new MethodJavaType(formals, returnType, thrownTypes, defSite.symbol);
    return resolution;
  }

  private static List<JavaType> erasure(List<JavaType> types) {
    List<JavaType> erasedTypes = new ArrayList<>(types.size());
    for (JavaType type : types) {
      erasedTypes.add(type.erasure());
    }
    return erasedTypes;
  }

  private static boolean applicableWithUncheckedConversion(JavaSymbol.MethodJavaSymbol candidate, JavaType callSite, List<JavaType> typeParams) {
    return !candidate.isStatic() && isRawTypeOfParametrizedType(callSite) && typeParams.isEmpty();
  }
  private static boolean isRawTypeOfParametrizedType(JavaType site) {
    return !site.isParameterized() && !site.symbol.typeVariableTypes.isEmpty();
  }

  private static boolean hasCompatibleArity(int formalArgSize, int argSize, boolean isVarArgs) {
    if(isVarArgs) {
      return argSize - formalArgSize >= -1;
    }
    return formalArgSize == argSize;
  }

  /**
   * @param argTypes types of arguments
   * @param formals  types of formal parameters of method
   */
  private boolean isArgumentsAcceptable(List<JavaType> argTypes, List<JavaType> formals, boolean isVarArgs, boolean autoboxing) {
    int argsSize = argTypes.size();
    int formalsSize = formals.size();
    int nbArgToCheck = argsSize - formalsSize;
    if (isVarArgs) {
      // check at least last parameter for varargs compatibility
      nbArgToCheck++;
    }
    for (int i = 1; i <= nbArgToCheck; i++) {
      ArrayJavaType lastFormal = (ArrayJavaType) formals.get(formalsSize - 1);
      JavaType argType = argTypes.get(argsSize - i);
      // check type of element of array or if we invoke with an array that it is a compatible array type
      if (!isAcceptableType(argType, lastFormal.elementType, autoboxing) && (nbArgToCheck != 1 || !isAcceptableType(argType, lastFormal, autoboxing))) {
        return false;
      }
    }
    for (int i = 0; i < argsSize - nbArgToCheck; i++) {
      if (!isAcceptableType(argTypes.get(i), formals.get(i), autoboxing)) {
        return false;
      }
    }
    return true;
  }

  private boolean isAcceptableType(JavaType arg, JavaType formal, boolean autoboxing) {
    if(arg.isTagged(JavaType.DEFERRED)) {
      List<JavaType> samMethodArgs = findSamMethodArgs(formal);
      if(((DeferredType) arg).tree().is(Tree.Kind.LAMBDA_EXPRESSION)) {
        return ((LambdaExpressionTree) ((DeferredType) arg).tree()).parameters().size() == samMethodArgs.size();
      }
      // we accept all deferred type as we will resolve this later.
      return true;
    }
    if(formal.isTagged(JavaType.TYPEVAR) && !arg.isTagged(JavaType.TYPEVAR)) {
      return subtypeOfTypeVar(arg, (TypeVariableJavaType) formal);
    }
    if (formal.isArray() && arg.isArray()) {
      return isAcceptableType(((ArrayJavaType) arg).elementType(), ((ArrayJavaType) formal).elementType(), autoboxing);
    }

    if (arg.isParameterized() || formal.isParameterized() || isWilcardType(arg) || isWilcardType(formal)) {
      return callWithRawType(arg, formal) || types.isSubtype(arg, formal) || isAcceptableByAutoboxing(arg, formal.erasure());
    }
    // fall back to behavior based on erasure
    return types.isSubtype(arg.erasure(), formal.erasure()) || (autoboxing && isAcceptableByAutoboxing(arg, formal.erasure()));
  }

  private boolean callWithRawType(JavaType arg, JavaType formal) {
    return formal.isParameterized() && !arg.isParameterized() && types.isSubtype(arg, formal.erasure());
  }

  private static boolean subtypeOfTypeVar(JavaType arg, TypeVariableJavaType formal) {
    for (JavaType bound : formal.bounds()) {
      if ((bound.isTagged(JavaType.TYPEVAR) && !subtypeOfTypeVar(arg, (TypeVariableJavaType) bound))
        || !arg.isSubtypeOf(bound)) {
        return false;
      }
    }
    return true;
  }

  private static boolean isWilcardType(JavaType type) {
    return type.isTagged(JavaType.WILDCARD);
  }

  private boolean isAcceptableByAutoboxing(JavaType expressionType, JavaType formalType) {
    if (expressionType.isPrimitive()) {
      return types.isSubtype(symbols.boxedTypes.get(expressionType), formalType);
    } else {
      JavaType unboxedType = symbols.boxedTypes.inverse().get(expressionType);
      if (unboxedType != null) {
        return types.isSubtype(unboxedType, formalType);
      }
    }
    return false;
  }

  /**
   * JLS7 15.12.2.5. Choosing the Most Specific Method
   */
  private JavaSymbol selectMostSpecific(JavaSymbol m1, JavaSymbol m2, List<JavaType> argTypes, TypeSubstitution substitution) {
    // FIXME get rig of null check
    if (m2.type == null || !m2.isKind(JavaSymbol.MTH)) {
      return m1;
    }
    boolean m1SignatureMoreSpecific = isSignatureMoreSpecific(m1, m2, argTypes, substitution);
    boolean m2SignatureMoreSpecific = isSignatureMoreSpecific(m2, m1, argTypes, substitution);
    if (m1SignatureMoreSpecific && m2SignatureMoreSpecific) {
      return new AmbiguityErrorJavaSymbol();
    } else if (m1SignatureMoreSpecific) {
      return m1;
    } else if (m2SignatureMoreSpecific) {
      return m2;
    }
    return new AmbiguityErrorJavaSymbol();
  }

  /**
   * @return true, if signature of m1 is more specific than signature of m2
   */
  private boolean isSignatureMoreSpecific(JavaSymbol m1, JavaSymbol m2, List<JavaType> argTypes, TypeSubstitution substitution) {
    List<JavaType> m1ArgTypes = ((MethodJavaType) m1.type).argTypes;
    List<JavaType> m2ArgTypes = ((MethodJavaType) m2.type).argTypes;
    JavaSymbol.MethodJavaSymbol methodJavaSymbol = (JavaSymbol.MethodJavaSymbol) m1;
    boolean m1VarArity = methodJavaSymbol.isVarArgs();
    boolean m2VarArity = ((JavaSymbol.MethodJavaSymbol) m2).isVarArgs();
    if (m1VarArity != m2VarArity) {
      // last arg is an array
      boolean lastArgIsArray = !argTypes.isEmpty() && argTypes.get(argTypes.size() -1).isArray() && (argTypes.size() == m2ArgTypes.size() || argTypes.size() == m1ArgTypes.size());
      // general case : prefer strict arity invocation over varArity, so if m2 is variadic, m1 is most specific, but not if last arg of invocation is an array
      return lastArgIsArray ^ m2VarArity;
    }
    if (m1VarArity) {
      m1ArgTypes = expandVarArgsToFitSize(m1ArgTypes, m2ArgTypes.size());
    }
    if(!hasCompatibleArity(m1ArgTypes.size(), m2ArgTypes.size(), m2VarArity)) {
      return false;
    }
    m1ArgTypes = typeSubstitutionSolver.applySubstitutionToFormalParameters(m1ArgTypes, substitution);
    return isArgumentsAcceptable(m1ArgTypes, m2ArgTypes, m2VarArity, false);
  }

  private static List<JavaType> expandVarArgsToFitSize(List<JavaType> m1ArgTypes, int size) {
    List<JavaType> newArgTypes = new ArrayList<>(m1ArgTypes);
    int m1ArgTypesSize = newArgTypes.size();
    int m1ArgTypesLast = m1ArgTypesSize - 1;
    Type lastElementType = ((Type.ArrayType) newArgTypes.get(m1ArgTypesLast)).elementType();
    // replace last element type from GivenType[] to GivenType
    newArgTypes.set(m1ArgTypesLast, (JavaType) lastElementType);
    // if m1ArgTypes smaller than size pad it with lastElementType
    for (int i = m1ArgTypesSize; i < size - 1; i++) {
      if (i < newArgTypes.size()) {
        newArgTypes.set(i, (JavaType) lastElementType);
      } else {
        newArgTypes.add((JavaType) lastElementType);
      }
    }
    return newArgTypes;
  }

  /**
   * Is class accessible in given environment?
   */
  @VisibleForTesting
  static boolean isAccessible(Env env, JavaSymbol.TypeJavaSymbol c) {
    final boolean result;
    switch (c.flags() & Flags.ACCESS_FLAGS) {
      case Flags.PRIVATE:
        result = sameOutermostClass(env.enclosingClass, c.owner());
        break;
      case 0:
        result = env.packge == c.packge();
        break;
      case Flags.PUBLIC:
        result = true;
        break;
      case Flags.PROTECTED:
        result = env.packge == c.packge() || isInnerSubClass(env.enclosingClass, c.owner());
        break;
      default:
        throw new IllegalStateException();
    }
    // TODO check accessibility of enclosing type: isAccessible(env, c.type.getEnclosingType())
    return result;
  }

  /**
   * Is given class a subclass of given base class, or an inner class of a subclass?
   */
  private static boolean isInnerSubClass(JavaSymbol.TypeJavaSymbol c, JavaSymbol base) {
    while (c != null && isSubClass(c, base)) {
      c = c.owner().enclosingClass();
    }
    return c != null;
  }

  /**
   * Is given class a subclass of given base class?
   */
  @VisibleForTesting
  static boolean isSubClass(JavaSymbol.TypeJavaSymbol c, JavaSymbol base) {
    // TODO get rid of null check
    if (c == null) {
      return false;
    }
    // TODO see Javac
    if (c == base) {
      // same class
      return true;
    } else if ((base.flags() & Flags.INTERFACE) != 0) {
      // check if class implements base
      for (JavaType interfaceType : c.getInterfaces()) {
        if (isSubClass(interfaceType.symbol, base)) {
          return true;
        }
      }
      // check if superclass implements base
      return isSubClass(superclassSymbol(c), base);
    } else {
      // check if class extends base or its superclass extends base
      return isSubClass(superclassSymbol(c), base);
    }
  }

  /**
   * Is symbol accessible as a member of given class in given environment?
   * <p/>
   * Symbol is accessible only if not overridden by another symbol. If overridden, then strictly speaking it is not a member.
   */
  private static boolean isAccessible(Env env, JavaSymbol.TypeJavaSymbol site, JavaSymbol symbol) {
    switch (symbol.flags() & Flags.ACCESS_FLAGS) {
      case Flags.PRIVATE:
        //if enclosing class is null, we are checking accessibility for imports so we return false.
        // no check of overriding, because private members cannot be overridden
        return env.enclosingClass != null && sameOutermostClass(env.enclosingClass, symbol.owner())
            && isInheritedIn(symbol, site);
      case 0:
        return (env.packge == symbol.packge())
            && isAccessible(env, site)
            && isInheritedIn(symbol, site)
            && notOverriddenIn(site, symbol);
      case Flags.PUBLIC:
        return isAccessible(env, site)
            && notOverriddenIn(site, symbol);
      case Flags.PROTECTED:
        return ((env.packge == symbol.packge()) || isProtectedAccessible(symbol, env.enclosingClass, site))
            && isAccessible(env, site)
            && notOverriddenIn(site, symbol);
      default:
        throw new IllegalStateException();
    }
  }

  static boolean sameOutermostClass(JavaSymbol s1, JavaSymbol s2) {
    return s1.outermostClass() == s2.outermostClass();
  }

  private static boolean notOverriddenIn(JavaSymbol.TypeJavaSymbol site, JavaSymbol symbol) {
    // TODO see Javac
    return true;
  }

  /**
   * Is symbol inherited in given class?
   */
  @VisibleForTesting
  static boolean isInheritedIn(JavaSymbol symbol, JavaSymbol.TypeJavaSymbol clazz) {
    switch (symbol.flags() & Flags.ACCESS_FLAGS) {
      case Flags.PUBLIC:
        return true;
      case Flags.PRIVATE:
        return symbol.owner() == clazz;
      case Flags.PROTECTED:
        // TODO see Javac
        return true;
      case 0:
        // TODO see Javac
        JavaSymbol.PackageJavaSymbol thisPackage = symbol.packge();
        for (JavaSymbol.TypeJavaSymbol sup = clazz; sup != null && sup != symbol.owner(); sup = superclassSymbol(sup)) {
          if (sup.packge() != thisPackage) {
            return false;
          }
        }
        return true;
      default:
        throw new IllegalStateException();
    }
  }

  private static boolean isProtectedAccessible(JavaSymbol symbol, JavaSymbol.TypeJavaSymbol c, JavaSymbol.TypeJavaSymbol site) {
    // TODO see Javac
    return true;
  }

  Type leastUpperBound(Set<Type> refTypes) {
    return typeSubstitutionSolver.leastUpperBound(refTypes);
  }

  Resolution unresolved() {
    Resolution resolution = new Resolution(symbolNotFound);
    resolution.type = Symbols.unknownType;
    return resolution;
  }

  public JavaType conditionalExpressionType(ConditionalExpressionTree tree, JavaType trueType, JavaType falseType) {
    if (trueType.isTagged(JavaType.DEFERRED) || falseType.isTagged(JavaType.DEFERRED)) {
      return symbols.deferedType((ConditionalExpressionTreeImpl) tree);
    }
    if (trueType == falseType) {
      return trueType;
    }
    if (trueType.isTagged(JavaType.BOT)) {
      return falseType.isPrimitive() ? falseType.primitiveWrapperType() : falseType;
    }
    if (falseType.isTagged(JavaType.BOT)) {
      return trueType.isPrimitive() ? trueType.primitiveWrapperType() : trueType;
    }
    JavaType secondOperand = getPrimitive(trueType);
    JavaType thirdOperand = getPrimitive(falseType);
    if (secondOperand != null && thirdOperand != null && isNumericalConditionalExpression(secondOperand, thirdOperand)) {
      // If operand is a constant int that can fits a narrow type it should be narrowed. We always narrow to approximate things properly for
      // method resolution.
      if ((secondOperand.tag < thirdOperand.tag || secondOperand.isTagged(JavaType.INT)) && !thirdOperand.isTagged(JavaType.INT)) {
        return thirdOperand;
      } else {
        return secondOperand;
      }
    }
    return (JavaType) leastUpperBound(Sets.<Type>newHashSet(trueType, falseType));
  }

  private static boolean isNumericalConditionalExpression(JavaType secondOperand, JavaType thirdOperand) {
    return secondOperand.isNumerical() && thirdOperand.isNumerical();
  }

  @CheckForNull
  private static JavaType getPrimitive(JavaType primitiveOrWrapper) {
    if(primitiveOrWrapper.isPrimitiveWrapper()) {
      return primitiveOrWrapper.primitiveType();
    }
    return primitiveOrWrapper.isPrimitive() ? primitiveOrWrapper : null;
  }

  public List<JavaType> findSamMethodArgs(Type type) {
    for (Symbol member : type.symbol().memberSymbols()) {
      if(member.isMethodSymbol() && member.isAbstract()) {
        JavaSymbol.MethodJavaSymbol target = (JavaSymbol.MethodJavaSymbol) member;
        List<JavaType> argTypes = ((MethodJavaType) target.type).argTypes;
        argTypes = typeSubstitutionSolver.applySiteSubstitutionToFormalParameters(argTypes, (JavaType) type);
        List<JavaType> result = new ArrayList();
        for (JavaType argType : argTypes) {
          if(argType.isTagged(JavaType.WILDCARD)) {
            // JLS8 9.9 Function types : this is approximated for ? extends X types (cf JLS)
            result.add(((WildCardType) argType).bound);
          } else {
            result.add(argType);
          }

        }
        return result;
      }
    }
    return new ArrayList<>();
  }

  /**
   * Resolution holds the symbol resolved and its type in this context.
   * This is required to handle type substitution for generics.
   */
  static class Resolution {

    private JavaSymbol symbol;
    private JavaType type;

    private Resolution(JavaSymbol symbol) {
      this.symbol = symbol;
    }

    Resolution() {
    }

    static Resolution resolution(JavaSymbol symbol) {
      return new Resolution(symbol);
    }

    JavaSymbol symbol() {
      return symbol;
    }

    public JavaType type() {
      if (type == null) {
        if(symbol.isKind(JavaSymbol.MTH)) {
          return ((MethodJavaType)symbol.type).resultType;
        }
        if(symbol.isUnknown() || symbol.isKind(JavaSymbol.PCK)) {
          return Symbols.unknownType;
        }
        return symbol.type;
      }
      return type;
    }
  }

  static class Env {
    /**
     * The next enclosing environment.
     */
    Env next;

    /**
     * The environment enclosing the current class.
     */
    @Nullable
    Env outer;

    JavaSymbol.PackageJavaSymbol packge;

    @Nullable
    JavaSymbol.TypeJavaSymbol enclosingClass;

    Scope scope;
    Scope namedImports;
    Scope starImports;
    Scope staticStarImports;

    public Env dup() {
      Env env = new Env();
      env.next = this;
      env.outer = this.outer;
      env.packge = this.packge;
      env.enclosingClass = this.enclosingClass;
      env.scope = this.scope;
      env.namedImports = this.namedImports;
      env.starImports = this.starImports;
      env.staticStarImports = this.staticStarImports;
      return env;
    }

  }

  public static class JavaSymbolNotFound extends JavaSymbol {
    public JavaSymbolNotFound() {
      super(JavaSymbol.ABSENT, 0, null, Symbols.unknownSymbol);
    }

    @Override
    public boolean isUnknown() {
      return true;
    }
  }

  public static class AmbiguityErrorJavaSymbol extends JavaSymbol {
    public AmbiguityErrorJavaSymbol() {
      super(JavaSymbol.AMBIGUOUS, 0, null, null);
    }
  }

  public static class AccessErrorJavaSymbol extends JavaSymbol {
    /**
     * The invalid symbol found during resolution.
     */
    JavaSymbol symbol;

    public AccessErrorJavaSymbol(JavaSymbol symbol, JavaType type) {
      super(JavaSymbol.ERRONEOUS, 0, null, null);
      this.symbol = symbol;
      this.type = type;
    }
  }
}
