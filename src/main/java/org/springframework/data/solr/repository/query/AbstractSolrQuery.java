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
package org.springframework.data.solr.repository.query;

import java.util.Collection;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.solr.common.params.HighlightParams;
import org.springframework.core.convert.support.GenericConversionService;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.geo.Distance;
import org.springframework.data.repository.core.EntityMetadata;
import org.springframework.data.repository.query.RepositoryQuery;
import org.springframework.data.solr.VersionUtil;
import org.springframework.data.solr.core.SolrOperations;
import org.springframework.data.solr.core.SolrTransactionSynchronizationAdapterBuilder;
import org.springframework.data.solr.core.convert.DateTimeConverters;
import org.springframework.data.solr.core.convert.NumberConverters;
import org.springframework.data.solr.core.geo.GeoConverters;
import org.springframework.data.solr.core.geo.GeoLocation;
import org.springframework.data.solr.core.query.FacetOptions;
import org.springframework.data.solr.core.query.FacetQuery;
import org.springframework.data.solr.core.query.HighlightOptions;
import org.springframework.data.solr.core.query.HighlightOptions.HighlightParameter;
import org.springframework.data.solr.core.query.HighlightQuery;
import org.springframework.data.solr.core.query.Query;
import org.springframework.data.solr.core.query.SimpleFacetQuery;
import org.springframework.data.solr.core.query.SimpleField;
import org.springframework.data.solr.core.query.SimpleHighlightQuery;
import org.springframework.data.solr.core.query.SimpleQuery;
import org.springframework.data.solr.core.query.SimpleStringCriteria;
import org.springframework.data.solr.core.query.result.FacetPage;
import org.springframework.data.solr.core.query.result.HighlightPage;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;

/**
 * Base implementation of a solr specific {@link RepositoryQuery}
 * 
 * @author Christoph Strobl
 * @author Luke Corpe
 * @author Andrey Paramonov
 * @author Francisco Spaeth
 */
public abstract class AbstractSolrQuery implements RepositoryQuery {

	private static final Pattern PARAMETER_PLACEHOLDER = Pattern.compile("\\?(\\d+)");

	private final SolrOperations solrOperations;
	private final SolrQueryMethod solrQueryMethod;

	private final GenericConversionService conversionService = new GenericConversionService();

	{
		if (!conversionService.canConvert(java.util.Date.class, String.class)) {
			conversionService.addConverter(DateTimeConverters.JavaDateConverter.INSTANCE);
		}
		if (!conversionService.canConvert(Number.class, String.class)) {
			conversionService.addConverter(NumberConverters.NumberConverter.INSTANCE);
		}
		if (!conversionService.canConvert(GeoLocation.class, String.class)) {
			conversionService.addConverter(GeoConverters.GeoLocationToStringConverter.INSTANCE);
		}
		if (!conversionService.canConvert(Distance.class, String.class)) {
			conversionService.addConverter(GeoConverters.DistanceToStringConverter.INSTANCE);
		}
		if (VersionUtil.isJodaTimeAvailable()) {
			if (!conversionService.canConvert(org.joda.time.ReadableInstant.class, String.class)) {
				conversionService.addConverter(DateTimeConverters.JodaDateTimeConverter.INSTANCE);
			}
			if (!conversionService.canConvert(org.joda.time.LocalDateTime.class, String.class)) {
				conversionService.addConverter(DateTimeConverters.JodaLocalDateTimeConverter.INSTANCE);
			}
		}
	}

	/**
	 * @param solrOperations must not be null
	 * @param solrQueryMethod must not be null
	 */
	protected AbstractSolrQuery(SolrOperations solrOperations, SolrQueryMethod solrQueryMethod) {
		Assert.notNull(solrOperations);
		Assert.notNull(solrQueryMethod);
		this.solrOperations = solrOperations;
		this.solrQueryMethod = solrQueryMethod;
	}

	@Override
	public Object execute(Object[] parameters) {
		SolrParameterAccessor accessor = new SolrParametersParameterAccessor(solrQueryMethod, parameters);

		Query query = createQuery(accessor);
		decorateWithFilterQuery(query, accessor);
		setDefaultQueryOperatorIfDefined(query);
		setAllowedQueryExeutionTime(query);
		setDefTypeIfDefined(query);
		setRequestHandlerIfDefined(query);

		if (isCountQuery() && isDeleteQuery()) {
			throw new InvalidDataAccessApiUsageException("Cannot execute 'delete' and 'count' at the same time.");
		}

		if (isCountQuery()) {
			return new CountExecution().execute(query);
		}
		if (isDeleteQuery()) {
			return new DeleteExecution().execute(query);
		}

		if (solrQueryMethod.isPageQuery()) {
			if (solrQueryMethod.isFacetQuery() && solrQueryMethod.isHighlightQuery()) {
				throw new InvalidDataAccessApiUsageException("Facet and Highlight cannot be combined.");
			}
			if (solrQueryMethod.isFacetQuery()) {
				FacetQuery facetQuery = SimpleFacetQuery.fromQuery(query, new SimpleFacetQuery());
				facetQuery.setFacetOptions(extractFacetOptions(solrQueryMethod, accessor));
				return new FacetPageExecution(accessor.getPageable()).execute(facetQuery);
			}
			if (solrQueryMethod.isHighlightQuery()) {
				HighlightQuery highlightQuery = SimpleHighlightQuery.fromQuery(query, new SimpleHighlightQuery());
				highlightQuery.setHighlightOptions(extractHighlightOptions(solrQueryMethod, accessor));
				return new HighlightPageExecution(accessor.getPageable()).execute(highlightQuery);
			}
			return new PagedExecution(accessor.getPageable()).execute(query);
		} else if (solrQueryMethod.isCollectionQuery()) {
			return new CollectionExecution(accessor.getPageable()).execute(query);
		}

		return new SingleEntityExecution().execute(query);
	}

	@Override
	public SolrQueryMethod getQueryMethod() {
		return this.solrQueryMethod;
	}

	private void setDefaultQueryOperatorIfDefined(Query query) {
		Query.Operator defaultOperator = solrQueryMethod.getDefaultOperator();
		if (defaultOperator != null && !Query.Operator.NONE.equals(defaultOperator)) {
			query.setDefaultOperator(defaultOperator);
		}
	}

	private void setAllowedQueryExeutionTime(Query query) {
		Integer timeAllowed = solrQueryMethod.getTimeAllowed();
		if (timeAllowed != null) {
			query.setTimeAllowed(timeAllowed);
		}
	}

	private void setDefTypeIfDefined(Query query) {
		String defType = solrQueryMethod.getDefType();
		if (StringUtils.hasText(defType)) {
			query.setDefType(defType);
		}
	}

	private void setRequestHandlerIfDefined(Query query) {
		String requestHandler = solrQueryMethod.getRequestHandler();
		if (StringUtils.hasText(requestHandler)) {
			query.setRequestHandler(requestHandler);
		}
	}

	private void decorateWithFilterQuery(Query query, SolrParameterAccessor parameterAccessor) {
		if (solrQueryMethod.hasFilterQuery()) {
			for (String filterQuery : solrQueryMethod.getFilterQueries()) {
				query.addFilterQuery(createQueryFromString(filterQuery, parameterAccessor));
			}
		}
	}

	protected void appendProjection(Query query) {
		if (query != null && this.getQueryMethod().hasProjectionFields()) {
			for (String fieldname : this.getQueryMethod().getProjectionFields()) {
				query.addProjectionOnField(new SimpleField(fieldname));
			}
		}
	}

	protected SimpleQuery createQueryFromString(String queryString, SolrParameterAccessor parameterAccessor) {
		String parsedQueryString = replacePlaceholders(queryString, parameterAccessor);
		return new SimpleQuery(new SimpleStringCriteria(parsedQueryString));
	}

	private String replacePlaceholders(String input, SolrParameterAccessor accessor) {
		if (!StringUtils.hasText(input)) {
			return input;
		}

		Matcher matcher = PARAMETER_PLACEHOLDER.matcher(input);
		String result = input;

		while (matcher.find()) {
			String group = matcher.group();
			int index = Integer.parseInt(matcher.group(1));
			result = result.replace(group, getParameterWithIndex(accessor, index));
		}
		return result;
	}

	@SuppressWarnings("rawtypes")
	private String getParameterWithIndex(SolrParameterAccessor accessor, int index) {
		Object parameter = accessor.getBindableValue(index);

		if (parameter == null) {
			return "null";
		}

		if (conversionService.canConvert(parameter.getClass(), String.class)) {
			return conversionService.convert(parameter, String.class);
		}

		if (parameter instanceof Collection) {
			StringBuilder sb = new StringBuilder();
			for (Object o : (Collection) parameter) {
				if (conversionService.canConvert(o.getClass(), String.class)) {
					sb.append(conversionService.convert(o, String.class));
				} else {
					sb.append(o.toString());
				}
				sb.append(" ");
			}
			return sb.toString().trim();
		}

		return parameter.toString();
	}

	private FacetOptions extractFacetOptions(SolrQueryMethod queryMethod, SolrParameterAccessor parameterAccessor) {
		FacetOptions options = new FacetOptions();
		if (queryMethod.hasFacetFields()) {
			options.addFacetOnFlieldnames(queryMethod.getFacetFields());
		}
		if (queryMethod.hasFacetQueries()) {
			for (String queryString : queryMethod.getFacetQueries()) {
				options.addFacetQuery(createQueryFromString(queryString, parameterAccessor));
			}
		}
		if (queryMethod.hasPivotFields()) {
			for (String pivot : queryMethod.getPivotFields()) {
				options.addFacetOnPivot(pivot);
			}
		}
		options.setFacetLimit(queryMethod.getFacetLimit());
		options.setFacetMinCount(queryMethod.getFacetMinCount());
		options.setFacetPrefix(replacePlaceholders(queryMethod.getFacetPrefix(), parameterAccessor));
		return options;
	}

	private HighlightOptions extractHighlightOptions(SolrQueryMethod queryMethod, SolrParameterAccessor accessor) {
		HighlightOptions options = new HighlightOptions();
		if (queryMethod.hasHighlightFields()) {
			options.addFields(queryMethod.getHighlightFieldNames());
		}
		Integer fragsize = queryMethod.getHighlightFragsize();
		if (fragsize != null) {
			options.setFragsize(fragsize);
		}
		Integer snipplets = queryMethod.getHighlighSnipplets();
		if (snipplets != null) {
			options.setNrSnipplets(snipplets);
		}
		String queryString = queryMethod.getHighlightQuery();
		if (queryString != null) {
			options.setQuery(createQueryFromString(queryString, accessor));
		}
		appendHighlightFormatOptions(options, solrQueryMethod);
		return options;
	}

	private void appendHighlightFormatOptions(HighlightOptions options, SolrQueryMethod queryMethod) {
		String formatter = queryMethod.getHighlightFormatter();
		if (formatter != null) {
			options.setFormatter(formatter);
		}
		String highlightPrefix = queryMethod.getHighlightPrefix();
		if (highlightPrefix != null) {
			if (isSimpleHighlightingOption(formatter)) {
				options.setSimplePrefix(highlightPrefix);
			} else {
				options.addHighlightParameter(new HighlightParameter(HighlightParams.TAG_PRE, highlightPrefix));
			}
		}
		String highlightPostfix = queryMethod.getHighlightPostfix();
		if (highlightPostfix != null) {
			if (isSimpleHighlightingOption(formatter)) {
				options.setSimplePostfix(highlightPostfix);
			} else {
				options.addHighlightParameter(new HighlightParameter(HighlightParams.TAG_POST, highlightPostfix));
			}
		}
	}

	private boolean isSimpleHighlightingOption(String formatter) {
		return formatter == null || HighlightParams.SIMPLE.equalsIgnoreCase(formatter);
	}

	protected abstract Query createQuery(SolrParameterAccessor parameterAccessor);

	/**
	 * @since 1.2
	 */
	public boolean isCountQuery() {
		return false;
	}

	/**
	 * @since 1.2
	 */
	public boolean isDeleteQuery() {
		return solrQueryMethod.isDeleteQuery();
	}

	private interface QueryExecution {
		Object execute(Query query);
	}

	/**
	 * Base class for query execution implementing {@link QueryExecution}
	 * 
	 * @author Christoph Strobl
	 */
	abstract class AbstractQueryExecution implements QueryExecution {

		protected Page<?> executeFind(Query query) {
			EntityMetadata<?> metadata = solrQueryMethod.getEntityInformation();
			return solrOperations.queryForPage(query, metadata.getJavaType());
		}

	}

	/**
	 * Implementation to query solr returning list of data without metadata. <br />
	 * If not pageable argument is set count operation will be executed to determine total number of entities to be
	 * fetched
	 * 
	 * @author Christoph Strobl
	 */
	class CollectionExecution extends AbstractQueryExecution {
		private final Pageable pageable;

		public CollectionExecution(Pageable pageable) {
			this.pageable = pageable;
		}

		@Override
		public Object execute(Query query) {
			query.setPageRequest(pageable != null ? pageable : new PageRequest(0, Math.max(1, (int) count(query))));
			return executeFind(query).getContent();
		}

		private long count(Query query) {
			return solrOperations.count(query);
		}

	}

	/**
	 * Implementation to query solr returning requested {@link Page}
	 * 
	 * @author Christoph Strobl
	 */
	class PagedExecution extends AbstractQueryExecution {
		private final Pageable pageable;

		public PagedExecution(Pageable pageable) {
			Assert.notNull(pageable);
			this.pageable = pageable;
		}

		@Override
		public Object execute(Query query) {
			query.setPageRequest(getPageable());
			return executeFind(query);
		}

		protected Pageable getPageable() {
			return this.pageable;
		}
	}

	/**
	 * Implementation to query solr retuning {@link FacetPage}
	 * 
	 * @author Christoph Strobl
	 */
	class FacetPageExecution extends PagedExecution {

		public FacetPageExecution(Pageable pageable) {
			super(pageable);
		}

		@Override
		protected FacetPage<?> executeFind(Query query) {
			Assert.isInstanceOf(FacetQuery.class, query);

			EntityMetadata<?> metadata = solrQueryMethod.getEntityInformation();
			return solrOperations.queryForFacetPage((FacetQuery) query, metadata.getJavaType());
		}

	}

	/**
	 * Implementation to execute query returning {@link HighlightPage}
	 * 
	 * @author Christoph Strobl
	 */
	class HighlightPageExecution extends PagedExecution {

		public HighlightPageExecution(Pageable pageable) {
			super(pageable);
		}

		protected HighlightPage<?> executeFind(Query query) {
			Assert.isInstanceOf(HighlightQuery.class, query);

			EntityMetadata<?> metadata = solrQueryMethod.getEntityInformation();
			return solrOperations.queryForHighlightPage((HighlightQuery) query, metadata.getJavaType());
		};

	}

	/**
	 * Implementation to query solr returning one single entity
	 * 
	 * @author Christoph Strobl
	 */
	class SingleEntityExecution implements QueryExecution {

		@Override
		public Object execute(Query query) {
			EntityMetadata<?> metadata = solrQueryMethod.getEntityInformation();
			return solrOperations.queryForObject(query, metadata.getJavaType());
		}
	}

	/**
	 * @since 1.2
	 */
	class CountExecution implements QueryExecution {

		@Override
		public Object execute(Query query) {
			return Long.valueOf(solrOperations.count(query));
		}

	}

	/**
	 * @since 1.2
	 */
	class DeleteExecution implements QueryExecution {

		@Override
		public Object execute(Query query) {

			if (TransactionSynchronizationManager.isSynchronizationActive()) {
				SolrTransactionSynchronizationAdapterBuilder.forOperations(solrOperations).withDefaultBehaviour().register();
			}

			Object result = countOrGetDocumentsForDelete(query);

			solrOperations.delete(query);
			if (!TransactionSynchronizationManager.isSynchronizationActive()) {
				solrOperations.commit();
			}

			return result;
		}

		private Object countOrGetDocumentsForDelete(Query query) {

			Object result = null;

			if (solrQueryMethod.isCollectionQuery()) {
				Query clone = SimpleQuery.fromQuery(query);
				result = solrOperations.queryForPage(clone.setPageRequest(new PageRequest(0, Integer.MAX_VALUE)),
						solrQueryMethod.getEntityInformation().getJavaType()).getContent();
			}

			if (ClassUtils.isAssignable(Number.class, solrQueryMethod.getReturnedObjectType())) {
				result = solrOperations.count(query);
			}
			return result;
		}
	}

}
