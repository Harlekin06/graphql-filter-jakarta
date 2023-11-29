/*
  Copyright 2020 Intuit Inc.

  Licensed under the Apache License, Version 2.0 (the "License");
  you may not use this file except in compliance with the License.
  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
 */
package com.intuit.graphql.filter.visitors;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import org.springframework.data.jpa.domain.Specification;

import com.intuit.graphql.filter.ast.BinaryExpression;
import com.intuit.graphql.filter.ast.CompoundExpression;
import com.intuit.graphql.filter.ast.Expression;
import com.intuit.graphql.filter.ast.ExpressionField;
import com.intuit.graphql.filter.ast.ExpressionValue;
import com.intuit.graphql.filter.ast.UnaryExpression;
import com.intuit.graphql.filter.client.FieldValuePair;
import com.intuit.graphql.filter.client.FieldValueTransformer;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;

/**
 * This class is responsible for traversing the expression tree and generating a
 * compound JPA Specification from it with correct precedence order.
 *
 * @author sjaiswal
 */
public class JpaSpecificationExpressionVisitor<T> implements ExpressionVisitor<Specification<T>> {

	private final Map<String, String> fieldMap;
	private final Deque<String> fieldStack;
	private final FieldValueTransformer fieldValueTransformer;

	public JpaSpecificationExpressionVisitor(final Map<String, String> fieldMap,
			final FieldValueTransformer fieldValueTransformer) {
		System.out.println("JpaSpecificationExpressionVisitor");
		this.fieldMap = fieldMap;
		this.fieldStack = new ArrayDeque<>();
		this.fieldValueTransformer = fieldValueTransformer;
	}

	/**
	 * Returns the JPA Specification from the expression tree.
	 * 
	 * @return
	 * @param expression
	 */
	@Override
	public Specification<T> expression(final Expression expression) {
		Specification<T> specification = null;
		if (expression != null) {
			specification = expression.accept(this, null);
		}
		return specification;
	}

	private ExpressionValue getTransformedValue(ExpressionValue<? extends Comparable> value) {
		if (!fieldStack.isEmpty() && fieldValueTransformer != null) {
			final String field = fieldStack.pop(); // pop the field associated with this value.
			final FieldValuePair fieldValuePair = fieldValueTransformer.transformValue(field, value.value());
			if (fieldValuePair != null && fieldValuePair.getValue() != null) {
				value = new ExpressionValue(fieldValuePair.getValue());
			}
		}
		return value;
	}

	private String mappedFieldName(final String fieldName) {
		final StringBuilder expressionBuilder = new StringBuilder();
		if (fieldMap != null && fieldMap.get(fieldName) != null) {
			expressionBuilder.append(fieldMap.get(fieldName));
		} else if (fieldValueTransformer != null && fieldValueTransformer.transformField(fieldName) != null) {
			expressionBuilder.append(fieldValueTransformer.transformField(fieldName));
			fieldStack.push(fieldName); // pushing the field for lookup while visiting value.
		} else {
			expressionBuilder.append(fieldName);
		}
		return expressionBuilder.toString();
	}

	/**
	 * Handles the processing of binary expression node.
	 * 
	 * @param binaryExpression Contains binary expression.
	 * @param data             Buffer for storing processed data.
	 * @return Data of processed node.
	 */
	@Override
	public Specification<T> visitBinaryExpression(final BinaryExpression binaryExpression,
			final Specification<T> data) {

		return new Specification<>() {
			@Override
			public Predicate toPredicate(final Root<T> root, final CriteriaQuery<?> criteriaQuery,
					final CriteriaBuilder criteriaBuilder) {

				ExpressionValue<? extends Comparable> operandValue = (ExpressionValue<? extends Comparable>) binaryExpression
						.getRightOperand();
				Predicate predicate = null;
				final String fieldName = mappedFieldName(binaryExpression.getLeftOperand().infix());
				operandValue = getTransformedValue(operandValue);
				final Path path = root.get(fieldName);

				switch (binaryExpression.getOperator()) {
				/* String operations. */
				case STARTS:
					predicate = criteriaBuilder.like(path, operandValue.value() + "%");
					break;

				case ENDS:
					predicate = criteriaBuilder.like(path, "%" + operandValue.value());
					break;

				case CONTAINS:
					predicate = criteriaBuilder.like(path, "%" + operandValue.value() + "%");
					break;

				case EQUALS:
					predicate = criteriaBuilder.equal(path, operandValue.value());
					break;

				/* Numeric operations. */
				case LT:
					predicate = criteriaBuilder.lessThan(path, operandValue.value());
					break;

				case LTE:
					predicate = criteriaBuilder.lessThanOrEqualTo(path, operandValue.value());
					break;

				case EQ:
					if (operandValue.value() == null) {
						predicate = criteriaBuilder.isNull(path);
					} else {
						predicate = criteriaBuilder.equal(path, operandValue.value());
					}
					break;

				case GT:
					predicate = criteriaBuilder.greaterThan(path, operandValue.value());
					break;

				case GTE:
					predicate = criteriaBuilder.greaterThanOrEqualTo(path, operandValue.value());
					break;

				case IN:
					final List<Comparable> expressionInValues = (List<Comparable>) operandValue.value();
					predicate = criteriaBuilder.in(path).value(expressionInValues);
					break;

				case BETWEEN:
					final List<Comparable> expressionBetweenValues = (List<Comparable>) operandValue.value();
					predicate = criteriaBuilder.between(path, expressionBetweenValues.get(0),
							expressionBetweenValues.get(1));
					break;
				}
				return predicate;
			}
		};
	}

	/**
	 * Handles the processing of compound expression node.
	 * 
	 * @param compoundExpression Contains compound expression.
	 * @param data               Buffer for storing processed data.
	 * @return Data of processed node.
	 */
	@Override
	public Specification<T> visitCompoundExpression(final CompoundExpression compoundExpression,
			final Specification<T> data) {
		Specification<T> result = null;
		switch (compoundExpression.getOperator()) {
		/* Logical operations. */
		case AND:
			Specification<T> left = compoundExpression.getLeftOperand().accept(this, null);
			Specification<T> right = compoundExpression.getRightOperand().accept(this, null);
			result = Specification.where(left).and(right);

			break;

		case OR:
			left = compoundExpression.getLeftOperand().accept(this, null);
			right = compoundExpression.getRightOperand().accept(this, null);
			result = Specification.where(left).or(right);
			break;
		}
		return result;
	}

	/**
	 * Handles the processing of expression field node.
	 * 
	 * @param field Contains expression field.
	 * @param data  Buffer for storing processed data.
	 * @return Data of processed node.
	 */
	@Override
	public Specification<T> visitExpressionField(final ExpressionField field, final Specification<T> data) {
		/* ExpressionField has been taken care in the Binary expression visitor. */
		return null;
	}

	/**
	 * Handles the processing of expression value node.
	 * 
	 * @param value Contains expression value.
	 * @param data  Buffer for storing processed data.
	 * @return Data of processed node.
	 */
	@Override
	public Specification<T> visitExpressionValue(final ExpressionValue<? extends Comparable> value,
			final Specification<T> data) {
		/* ExpressionValue has been taken care in the Binary expression visitor. */
		return null;
	}

	/**
	 * Handles the processing of unary expression node.
	 * 
	 * @param unaryExpression Contains unary expression.
	 * @param data            Buffer for storing processed data.
	 * @return Data of processed node.
	 */
	@Override
	public Specification<T> visitUnaryExpression(final UnaryExpression unaryExpression, final Specification<T> data) {
		final Specification<T> left = unaryExpression.getLeftOperand().accept(this, null);
		return Specification.not(left);
	}
}
