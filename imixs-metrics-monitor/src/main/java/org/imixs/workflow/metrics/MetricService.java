/****************************************************************************
 * Copyright (c) 2022-2025 Imixs Software Solutions GmbH and others.
 * https://www.imixs.com
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0
 *
 * This Source Code may also be made available under the terms of the
 * GNU General Public License, version 2 or later (GPL-2.0-or-later),
 * which available at https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * SPDX-License-Identifier: EPL-2.0 OR GPL-2.0-or-later
 ****************************************************************************/

package org.imixs.workflow.metrics;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.imixs.workflow.WorkflowKernel;
import org.imixs.workflow.engine.DocumentEvent;
import org.imixs.workflow.engine.ProcessingEvent;
import org.imixs.workflow.exceptions.AccessDeniedException;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

/**
 * The Imixs MetricService is a monitoring resource for Imixs-Workflow in the
 * prometheus format. This implementation uses Micrometer embedded in the WAR
 * for cross-server compatibility (WildFly, Payara, Open Liberty).
 * 
 * @author rsoika
 * @version 2.0
 */
@ApplicationScoped
public class MetricService {

	public static final String METRIC_DOCUMENTS = "imixs_documents_total";
	public static final String METRIC_WORKITEMS = "imixs_workitems_total";
	public static final String METRIC_TRANSACTIONS = "imixs_transactions_total";

	@Inject
	@ConfigProperty(name = "metrics.enabled", defaultValue = "false")
	private boolean metricsEnabled;

	@Inject
	@ConfigProperty(name = "metrics.anonymised", defaultValue = "true")
	private boolean metricsAnonymised;

	@Inject
	private MeterRegistry meterRegistry;

	// Cache für Counter-Instanzen um Performance zu verbessern
	private final ConcurrentHashMap<String, Counter> counterCache = new ConcurrentHashMap<>();

	private static final Logger logger = Logger.getLogger(MetricService.class.getName());

	@PostConstruct
	public void init() {
		if (metricsEnabled) {
			logger.info("Imixs MetricService initialized - metrics enabled: " + metricsEnabled);
			logger.info("Metrics anonymised: " + metricsAnonymised);
			logger.info("Using MeterRegistry: " + meterRegistry.getClass().getSimpleName());
		}
	}

	/**
	 * ProcessingEvent listener to generate a metric.
	 */
	public void onProcessingEvent(@Observes ProcessingEvent processingEvent) throws AccessDeniedException {
		if (!metricsEnabled || processingEvent == null) {
			return;
		}

		try {
			Counter counter = getOrCreateWorkitemMetric(processingEvent);
			counter.increment();
		} catch (Exception e) {
			logger.warning("Micrometer metric could not be updated: " + e.getMessage());
		}
	}

	/**
	 * DocumentEvent listener to generate a metric.
	 */
	public void onDocumentEvent(@Observes DocumentEvent documentEvent) throws AccessDeniedException {
		if (!metricsEnabled || documentEvent == null) {
			return;
		}

		try {
			Counter counter = getOrCreateDocumentMetric(documentEvent);
			counter.increment();
		} catch (Exception e) {
			logger.warning("Micrometer metric could not be updated: " + e.getMessage());
		}
	}

	/**
	 * Erstellt oder holt einen gecachten Counter für Document-Events
	 */
	private Counter getOrCreateDocumentMetric(DocumentEvent event) {
		String method = switch (event.getEventType()) {
		case DocumentEvent.ON_DOCUMENT_SAVE -> "save";
		case DocumentEvent.ON_DOCUMENT_LOAD -> "load";
		case DocumentEvent.ON_DOCUMENT_DELETE -> "delete";
		default -> "unknown";
		};

		String cacheKey = METRIC_DOCUMENTS + "_" + method;

		return counterCache.computeIfAbsent(cacheKey, key -> Counter.builder(METRIC_DOCUMENTS)
				.description("Total number of document operations")
				.tag("method", method)
				.register(meterRegistry));
	}

	/**
	 * Erstellt oder holt einen gecachten Counter für Workitem-Events
	 */
	private Counter getOrCreateWorkitemMetric(ProcessingEvent event) {
		if (event.getEventType() == ProcessingEvent.BEFORE_PROCESS) {
			return counterCache.computeIfAbsent(METRIC_TRANSACTIONS, key -> Counter.builder(METRIC_TRANSACTIONS)
					.description("Total number of workflow transactions")
					.register(meterRegistry));
		} else {
			// Tags für den Counter erstellen
			Tags tags = buildWorkitemTags(event);
			String cacheKey = METRIC_WORKITEMS + "_" + tags.toString().hashCode();

			return counterCache.computeIfAbsent(cacheKey, key -> Counter.builder(METRIC_WORKITEMS)
					.description("Total number of processed workitems")
					.tags(tags)
					.register(meterRegistry));
		}
	}

	/**
	 * Erstellt die Tags für Workitem-Metriken
	 */
	private Tags buildWorkitemTags(ProcessingEvent event) {
		List<Tag> tagList = new ArrayList<>();

		// Basis-Tags
		tagList.add(Tag.of("type", sanitizeTagValue(event.getDocument().getType())));
		tagList.add(Tag.of("modelversion", sanitizeTagValue(event.getDocument().getModelVersion())));
		tagList.add(Tag.of("task", String.valueOf(event.getDocument().getTaskID())));
		tagList.add(Tag.of("workflowgroup",
				sanitizeTagValue(event.getDocument().getItemValueString(WorkflowKernel.WORKFLOWGROUP))));
		tagList.add(Tag.of("workflowstatus",
				sanitizeTagValue(event.getDocument().getItemValueString(WorkflowKernel.WORKFLOWSTATUS))));
		tagList.add(Tag.of("event", String.valueOf(event.getDocument().getItemValueInteger("$lastevent"))));

		// User-Tag nur wenn nicht anonymisiert
		if (!metricsAnonymised) {
			String user = sanitizeTagValue(event.getDocument().getItemValueString(WorkflowKernel.EDITOR));
			tagList.add(Tag.of("user", user));
		}

		return Tags.of(tagList);
	}

	/**
	 * Bereinigt Tag-Werte für Prometheus (entfernt problematische Zeichen)
	 */
	private String sanitizeTagValue(String value) {
		if (value == null || value.trim().isEmpty()) {
			return "unknown";
		}
		// Ersetze problematische Zeichen für Prometheus
		return value.replaceAll("[^a-zA-Z0-9_-]", "_");
	}

	/**
	 * Gibt Prometheus-Format zurück (nur für embedded Registry)
	 */
	public String scrape() {
		if (!metricsEnabled) {
			return "# Metrics disabled\n";
		}

		if (meterRegistry instanceof PrometheusMeterRegistry prometheusRegistry) {
			return prometheusRegistry.scrape();
		}

		// Fallback für andere Registry-Typen
		return "# Prometheus format not available for registry type: " + meterRegistry.getClass().getSimpleName()
				+ "\n";
	}

	/**
	 * Gibt Statistiken über den Service zurück (für Monitoring/Debugging)
	 */
	public int getCachedMetricsCount() {
		return counterCache.size();
	}

	/**
	 * Leert den Cache (nur für Tests/Debugging)
	 */
	public void clearCache() {
		counterCache.clear();
	}
}