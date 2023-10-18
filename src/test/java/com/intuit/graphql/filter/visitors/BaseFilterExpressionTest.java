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

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.stream.Collectors;

import org.junit.Before;

import com.intuit.graphql.filter.common.EmployeeDataFetcher;

import graphql.GraphQL;
import graphql.schema.GraphQLSchema;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;

/**
 * @author sjaiswal
 */
public abstract class BaseFilterExpressionTest {

	private GraphQL graphQL;
	private EmployeeDataFetcher employeeDataFetcher;

	private GraphQLSchema buildSchema(final String sdl) {
		final TypeDefinitionRegistry typeRegistry = new SchemaParser().parse(sdl);
		final RuntimeWiring runtimeWiring = buildWiring();
		final SchemaGenerator schemaGenerator = new SchemaGenerator();
		return schemaGenerator.makeExecutableSchema(typeRegistry, runtimeWiring);
	}

	protected abstract RuntimeWiring buildWiring();

	public EmployeeDataFetcher getEmployeeDataFetcher() {
		return employeeDataFetcher;
	}

	public GraphQL getGraphQL() {
		return graphQL;
	}

	@Before
	public void init() throws IOException, URISyntaxException {

		employeeDataFetcher = new EmployeeDataFetcher();

		final URI filePath = getClass().getClassLoader().getResource("schema.graphql").toURI();
		final String sdl = Files.lines(Paths.get(filePath), StandardCharsets.UTF_8)
				.collect(Collectors.joining(System.lineSeparator()));

		final GraphQLSchema graphQLSchema = buildSchema(sdl);
		graphQL = GraphQL.newGraphQL(graphQLSchema).build();
	}
}
