/*
 * Copyright 2012 - 2014 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.data.solr.repository.support;

import static org.springframework.data.querydsl.QueryDslUtils.*;

import java.io.Serializable;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.WeakHashMap;

import org.apache.solr.client.solrj.SolrServer;
import org.springframework.data.querydsl.QueryDslPredicateExecutor;
import org.springframework.data.repository.core.NamedQueries;
import org.springframework.data.repository.core.RepositoryMetadata;
import org.springframework.data.repository.core.support.RepositoryFactorySupport;
import org.springframework.data.repository.query.QueryLookupStrategy;
import org.springframework.data.repository.query.QueryLookupStrategy.Key;
import org.springframework.data.repository.query.RepositoryQuery;
import org.springframework.data.solr.core.SolrOperations;
import org.springframework.data.solr.core.SolrTemplate;
import org.springframework.data.solr.repository.SolrRepository;
import org.springframework.data.solr.repository.query.PartTreeSolrQuery;
import org.springframework.data.solr.repository.query.SolrEntityInformation;
import org.springframework.data.solr.repository.query.SolrEntityInformationCreator;
import org.springframework.data.solr.repository.query.SolrQueryMethod;
import org.springframework.data.solr.repository.query.StringBasedSolrQuery;
import org.springframework.data.solr.server.SolrServerFactory;
import org.springframework.data.solr.server.support.MulticoreSolrServerFactory;
import org.springframework.data.solr.server.support.SolrServerUtils;
import org.springframework.util.Assert;

/**
 * Factory to create {@link SolrRepository}
 * 
 * @author Christoph Strobl
 */
public class SolrRepositoryFactory extends RepositoryFactorySupport {

	private SolrOperations solrOperations;
	private final SolrEntityInformationCreator entityInformationCreator;
	private SolrServerFactory factory;
	private SolrTemplateHolder templateHolder = new SolrTemplateHolder();

	public SolrRepositoryFactory(SolrOperations solrOperations) {
		Assert.notNull(solrOperations);
		this.solrOperations = solrOperations;
		this.entityInformationCreator = new SolrEntityInformationCreatorImpl(solrOperations.getConverter()
				.getMappingContext());
	}

	public SolrRepositoryFactory(SolrServer solrServer) {
		Assert.notNull(solrServer);
		this.solrOperations = new SolrTemplate(solrServer);
		factory = new MulticoreSolrServerFactory(solrServer);
		this.entityInformationCreator = new SolrEntityInformationCreatorImpl(this.solrOperations.getConverter()
				.getMappingContext());
	}

	@Override
	public <T, ID extends Serializable> SolrEntityInformation<T, ID> getEntityInformation(Class<T> domainClass) {
		return entityInformationCreator.getEntityInformation(domainClass);
	}

	@Override
	@SuppressWarnings({ "rawtypes", "unchecked" })
	protected Object getTargetRepository(RepositoryMetadata metadata) {
		SolrOperations operations = this.solrOperations;
		if (factory != null) {
			SolrTemplate template = new SolrTemplate(factory);
			template.setSolrCore(SolrServerUtils.resolveSolrCoreName(metadata.getDomainType()));
			template.afterPropertiesSet();
			operations = template;
		}

		SimpleSolrRepository repository = new SimpleSolrRepository(getEntityInformation(metadata.getDomainType()),
				operations);
		repository.setEntityClass(metadata.getDomainType());

		this.templateHolder.add(metadata.getDomainType(), operations);
		return repository;
	}

	@Override
	protected Class<?> getRepositoryBaseClass(RepositoryMetadata metadata) {
		if (isQueryDslRepository(metadata.getRepositoryInterface())) {
			throw new IllegalArgumentException("QueryDsl Support has not been implemented yet.");
		}
		return SimpleSolrRepository.class;
	}

	private static boolean isQueryDslRepository(Class<?> repositoryInterface) {
		return QUERY_DSL_PRESENT && QueryDslPredicateExecutor.class.isAssignableFrom(repositoryInterface);
	}

	@Override
	protected QueryLookupStrategy getQueryLookupStrategy(Key key) {
		return new SolrQueryLookupStrategy();
	}

	private class SolrQueryLookupStrategy implements QueryLookupStrategy {

		@Override
		public RepositoryQuery resolveQuery(Method method, RepositoryMetadata metadata, NamedQueries namedQueries) {

			SolrQueryMethod queryMethod = new SolrQueryMethod(method, metadata, entityInformationCreator);
			String namedQueryName = queryMethod.getNamedQueryName();

			SolrOperations solrOperations = selectSolrOperations(metadata);

			if (namedQueries.hasQuery(namedQueryName)) {
				String namedQuery = namedQueries.getQuery(namedQueryName);
				return new StringBasedSolrQuery(namedQuery, queryMethod, solrOperations);
			} else if (queryMethod.hasAnnotatedQuery()) {
				return new StringBasedSolrQuery(queryMethod, solrOperations);
			} else {
				return new PartTreeSolrQuery(queryMethod, solrOperations);
			}
		}

		private SolrOperations selectSolrOperations(RepositoryMetadata metadata) {
			SolrOperations ops = templateHolder.getSolrOperations(metadata.getDomainType());
			if (ops == null) {
				ops = solrOperations;
			}
			return ops;
		}

	}

	private static class SolrTemplateHolder {

		private Map<Class<?>, SolrOperations> operationsMap = new WeakHashMap<Class<?>, SolrOperations>();

		void add(Class<?> domainType, SolrOperations repository) {
			operationsMap.put(domainType, repository);
		}

		SolrOperations getSolrOperations(Class<?> type) {
			return operationsMap.get(type);
		}
	}

}
