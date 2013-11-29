/*******************************************************************************
 * Copyright (c) 2013 GK Software AG.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * This is an implementation of an early-draft specification developed under the Java
 * Community Process (JCP) and is made available for testing and evaluation purposes
 * only. The code is not compatible with any specification of the JCP.
 *
 * Contributors:
 *     Stephan Herrmann - initial API and implementation
 *******************************************************************************/
package org.eclipse.jdt.internal.compiler.lookup;

import org.eclipse.jdt.internal.compiler.ast.Wildcard;

/**
 * Implementation of 18.1.2 in JLS8, cases:
 * <ul>
 * <li>S -> T <em>compatible</em></li>
 * <li>S <: T <em>subtype</em></li>
 * <li>S = T  <em>equality</em></li>
 * <li>S <= T <em>type argument containment</em></li>
 * </ul>
 */
class ConstraintTypeFormula extends ConstraintFormula {

	TypeBinding left;
	
	public ConstraintTypeFormula(TypeBinding exprType, TypeBinding right, int relation) {
		this.left = exprType;
		this.right = right;
		this.relation = relation;
	}

	// return: ReductionResult or ConstraintFormula[]
	public Object reduce(InferenceContext18 inferenceContext) {
		switch (this.relation) {
		case COMPATIBLE:
			// 18.2.2:
			if (this.left.isProperType(true) && this.right.isProperType(true)) {
				if (isCompatibleWithInLooseInvocationContext(this.left, this.right, inferenceContext))
					return TRUE;
				return FALSE;
			}
			if (this.left.isBaseType() && this.left != TypeBinding.NULL) {
				TypeBinding sPrime = inferenceContext.environment.computeBoxingType(this.left);
				return new ConstraintTypeFormula(sPrime, this.right, COMPATIBLE);
			}
			if (this.right.isBaseType() && this.right != TypeBinding.NULL) {
				TypeBinding tPrime = inferenceContext.environment.computeBoxingType(this.right);
				return new ConstraintTypeFormula(this.left, tPrime, COMPATIBLE);
			}
			return new ConstraintTypeFormula(this.left, this.right, SUBTYPE);
		case SUBTYPE:
			// 18.2.3:
			return reduceSubType(inferenceContext.scope, this.left, this.right);
		case SUPERTYPE:
			// 18.2.3:
			return reduceSubType(inferenceContext.scope, this.right, this.left);
		case SAME:
			// 18.2.4:
			return reduceTypeEquality();
		case TYPE_ARGUMENT_CONTAINED:
			// 18.2.3:
			if (this.right.kind() != Binding.WILDCARD_TYPE) { // "If T is a type" ... all alternatives require "wildcard"
				if (this.left.kind() != Binding.WILDCARD_TYPE) {
					return new ConstraintTypeFormula(this.left, this.right, SAME);						
				} else {
					return FALSE;
				}
			} else {
				WildcardBinding t = (WildcardBinding) this.right;
				if (t.boundKind == Wildcard.UNBOUND || t.bound.id == TypeIds.T_JavaLangObject)
					return TRUE;
				if (t.boundKind == Wildcard.EXTENDS) {
					if (this.left.kind() != Binding.WILDCARD_TYPE) {
						return new ConstraintTypeFormula(this.left, t.bound, SUBTYPE);
					} else {
						WildcardBinding s = (WildcardBinding) this.left;
						if (s.boundKind == Wildcard.EXTENDS) {
							return new ConstraintTypeFormula(s.bound, t.bound, SUBTYPE);
						} else {
							return FALSE;
						}
					}
				} else { // SUPER 
					if (this.left.kind() != Binding.WILDCARD_TYPE) {
						return new ConstraintTypeFormula(t.bound, this.left, SUBTYPE);
					} else {
						WildcardBinding s = (WildcardBinding) this.left;
						if (s.boundKind == Wildcard.SUPER) {
							return new ConstraintTypeFormula(t.bound, s.bound, SUBTYPE);
						} else {
							return FALSE;
						}
					}
				}
			}
		default: throw new IllegalStateException("Unexpected relation kind "+this.relation); //$NON-NLS-1$
		}
	}

	private Object reduceTypeEquality() {
		// 18.2.4
		if (this.left.kind() == Binding.WILDCARD_TYPE) {
			if (this.right.kind() == Binding.WILDCARD_TYPE) {
				WildcardBinding leftWC = (WildcardBinding)this.left;
				WildcardBinding rightWC = (WildcardBinding)this.right;
				if (leftWC.bound == null && rightWC.bound == null)
					return TRUE;
				if ((leftWC.boundKind == Wildcard.EXTENDS && rightWC.boundKind == Wildcard.EXTENDS)
					||(leftWC.boundKind == Wildcard.SUPER && rightWC.boundKind == Wildcard.SUPER))
				{
					return new ConstraintTypeFormula(leftWC.bound, rightWC.bound, SAME);
				}						
			}
		} else {
			if (this.right.kind() != Binding.WILDCARD_TYPE) {
				// left and right are types (vs. wildcards)
				if (this.left.isProperType(true) && this.right.isProperType(true)) {
					if (TypeBinding.equalsEquals(this.left, this.right))
						return TRUE;
					return FALSE;
				}
				if (this.left instanceof InferenceVariable) {
					return new TypeBound((InferenceVariable) this.left, this.right, SAME);
				}
				if (this.right instanceof InferenceVariable) {
					return new TypeBound((InferenceVariable) this.right, this.left, SAME);
				}
				if (TypeBinding.equalsEquals(this.left.original(), this.right.original())) {
					TypeBinding[] leftParams = this.left.typeArguments();
					TypeBinding[] rightParams = this.right.typeArguments();
					if (leftParams == null || rightParams == null)
						return leftParams == rightParams ? TRUE : FALSE;
					if (leftParams.length != rightParams.length)
						return FALSE;
					int len = leftParams.length;
					ConstraintFormula[] constraints = new ConstraintFormula[len];
					for (int i = 0; i < len; i++) {
						constraints[i] = new ConstraintTypeFormula(leftParams[i], rightParams[i], SAME);
					}
					return constraints;
				}
				if (this.left.isArrayType() && this.right.isArrayType() && this.left.dimensions() == this.right.dimensions()) {
					// checking dimensions already now is an optimization over reducing one dim at a time
					return new ConstraintTypeFormula(this.left.leafComponentType(), this.right.leafComponentType(), SAME);
				}
				if (this.left.kind() == Binding.INTERSECTION_TYPE && this.right.kind() == Binding.INTERSECTION_TYPE) {
					InferenceContext18.missingImplementation("Intersection type equality NYI");
				}
			}
		}
		return FALSE;
	}

	private Object reduceSubType(Scope scope, TypeBinding subCandidate, TypeBinding superCandidate) {
		// 18.2.3 Subtyping Constraints
		if (subCandidate.isProperType(true) && superCandidate.isProperType(true)) {
			if (subCandidate.isCompatibleWith(superCandidate, scope))
				return TRUE;
			return FALSE;
		}
		if (subCandidate instanceof InferenceVariable)
			return new TypeBound((InferenceVariable)subCandidate, superCandidate, SUBTYPE);
		if (superCandidate instanceof InferenceVariable)
			return new TypeBound((InferenceVariable)superCandidate, subCandidate, SUPERTYPE); // normalize to have variable on LHS
		if (subCandidate.id == TypeIds.T_null)
			return TRUE;
		switch (superCandidate.kind()) {
			case Binding.GENERIC_TYPE:
			case Binding.TYPE:
			case Binding.RAW_TYPE: // TODO: check special handling of raw types?
				{
					ReferenceBinding c = (ReferenceBinding) superCandidate;
					if (subCandidate instanceof ReferenceBinding) {
						ReferenceBinding s = (ReferenceBinding) subCandidate;
						if (TypeBinding.equalsEquals(s.original(), c))
							return TRUE;
						if (TypeBinding.equalsEquals(s.superclass(), c))
							return TRUE;
						ReferenceBinding[] superInterfaces = s.superInterfaces();
						if (superInterfaces != null) {
							for (int i=0, l=superInterfaces.length; i<l; i++)
								if (TypeBinding.equalsEquals(superInterfaces[i], c))
									return TRUE;
						}
					}
					return FALSE;
				}
			case Binding.PARAMETERIZED_TYPE:
				{
					ParameterizedTypeBinding ca = (ParameterizedTypeBinding) superCandidate;	// C<A1,A2,...>
					TypeBinding[] ai = ca.arguments;
					TypeBinding cb = subCandidate.findSuperTypeOriginatingFrom(superCandidate);	// C<B1,B2,...>
					if (cb == null) return FALSE;
					if (cb.isRawType())
						// return FALSE; // this would conform to the spec
						return ILLEGAL_UNCHECKED_CONVERSION; // to conform with javac, see 	https://bugs.openjdk.java.net/browse/JDK-8026527
					TypeBinding[] bi = ((ParameterizedTypeBinding) cb).arguments;
					ConstraintFormula[] results = new ConstraintFormula[ai.length];
					for (int i = 0; i < ai.length; i++)
						results[i] = new ConstraintTypeFormula(bi[i], ai[i], TYPE_ARGUMENT_CONTAINED);
					return results;
				}
			case Binding.ARRAY_TYPE:
				TypeBinding tPrime = ((ArrayBinding)superCandidate).elementsType();
				// let S'[] be the most specific array type that is a supertype of S (or S itself)
				ArrayBinding sPrimeArray = null;
				switch(subCandidate.kind()) {
				case Binding.INTERSECTION_TYPE:
					{
						WildcardBinding intersection = (WildcardBinding) subCandidate;
						int numArrayBounds = 0;
						if (intersection.bound.isArrayType()) numArrayBounds++;
						for (int i = 0; i < intersection.otherBounds.length; i++) {
							if (intersection.otherBounds[i].isArrayType()) numArrayBounds++;
						}
						if (numArrayBounds == 0)
							return FALSE;
						InferenceContext18.missingImplementation("Cannot filter most specific array type");
						// FIXME assign sPrime
						break;
					}
				case Binding.ARRAY_TYPE:
					sPrimeArray = (ArrayBinding) subCandidate;
					break;
				default:					
					return FALSE;
				}
				TypeBinding sPrime = sPrimeArray.elementsType();
				if (!tPrime.isBaseType() && !sPrime.isBaseType()) {
					return new ConstraintTypeFormula(sPrime, tPrime, SUBTYPE);
				}
				return TypeBinding.equalsEquals(tPrime, sPrime) ? TRUE : FALSE; // same primitive type?

			// "type variable" has two implementations in JDT:
			case Binding.WILDCARD_TYPE:
				// TODO If S is an intersection type of which T is an element, the constraint reduces to true. 
				if (subCandidate.kind() == Binding.INTERSECTION_TYPE)
					InferenceContext18.missingImplementation("NYI");
				WildcardBinding variable = (WildcardBinding) superCandidate;
				if (variable.boundKind == Wildcard.SUPER)
					return new ConstraintTypeFormula(subCandidate, variable.bound, SUBTYPE);
				return FALSE;
			case Binding.TYPE_PARAMETER:
				// same as wildcard (but we don't have a lower bound any way)
				// TODO If S is an intersection type of which T is an element, the constraint reduces to true.
				if (subCandidate.kind() == Binding.INTERSECTION_TYPE)
					InferenceContext18.missingImplementation("NYI");
				return FALSE;
			case Binding.INTERSECTION_TYPE:
				InferenceContext18.missingImplementation("NYI");
		}
		if (superCandidate.id == TypeIds.T_null)
			return FALSE;
		throw new IllegalStateException("Unexpected RHS "+superCandidate); //$NON-NLS-1$
	}

	public void applySubstitution(BoundSet solutionSet, InferenceVariable[] variables) {
		super.applySubstitution(solutionSet, variables);
		for (int i=0; i<variables.length; i++) {
			InferenceVariable variable = variables[i];
			TypeBinding instantiation = solutionSet.getInstantiation(variables[i]);
			this.left = this.left.substituteInferenceVariable(variable, instantiation);
		}
	}

	// debugging
	public String toString() {
		StringBuffer buf = new StringBuffer("Type Constraint:\n"); //$NON-NLS-1$
		buf.append("\t⟨").append(this.left.readableName()); //$NON-NLS-1$
		buf.append(relationToString(this.relation));
		buf.append(this.right.readableName()).append('⟩');
		return buf.toString();
	}
}