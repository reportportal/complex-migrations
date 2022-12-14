package com.epam.reportportal.service.impl;

import com.epam.reportportal.elastic.SimpleElasticSearchClient;
import com.epam.reportportal.model.LogMessage;
import com.epam.reportportal.service.ElasticMigrationService;
import com.epam.reportportal.utils.LogRowMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class ElasticMigrationServiceImpl implements ElasticMigrationService {

	private final int maxLogNumber;
	private final LocalDateTime startDateTime;
	private final Logger LOGGER = LoggerFactory.getLogger(this.getClass());
	private final SimpleElasticSearchClient elasticSearchClient;
	private final JdbcTemplate jdbcTemplate;
	private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;

	private static final int DEFAULT_MAX_LOGS_NUMBER = 1000;
	private static final String SELECT_FIRST_ID = "SELECT MIN(id) FROM log";
	private static final String SELECT_LOG_ID_CLOSEST_TO_TIME = "SELECT id FROM log WHERE log_time >= :time ORDER BY id LIMIT 1";
	private static final String SELECT_ALL_LOGS_WITH_LAUNCH_ID =
			"SELECT id, log_time, log_message, item_id, launch_id, project_id FROM log "
					+ "WHERE launch_id IS NOT NULL ORDER BY id DESC LIMIT :maxLogNumber";
	private static final String SELECT_ALL_LOGS_WITHOUT_LAUNCH_ID =
			"SELECT l.id, log_time, log_message, l.item_id AS item_id, ti.launch_id AS launch_id, project_id FROM log l "
					+ "JOIN test_item ti ON l.item_id = ti.item_id "
					+ "UNION SELECT l.id, log_time, log_message, l.item_id AS item_id, ti.launch_id AS launch_id, project_id FROM log l "
					+ "JOIN test_item ti ON l.item_id = ti.item_id WHERE retry_of IS NOT NULL "
					+ "AND retry_of IN (SELECT item_id FROM test_item) ORDER BY id DESC LIMIT :maxLogNumber";
	private static final String SELECT_LOGS_WITH_LAUNCH_ID_BEFORE_ID =
			"SELECT id, log_time, log_message, item_id, launch_id, project_id FROM log WHERE launch_id IS NOT NULL AND id < :id"
					+ " ORDER BY id DESC LIMIT :maxLogNumber";
	private static final String SELECT_LOGS_WITHOUT_LAUNCH_ID_BEFORE_ID =
			"SELECT l.id, log_time, log_message, l.item_id AS item_id, ti.launch_id AS launch_id, project_id FROM log l "
					+ "JOIN test_item ti ON l.item_id = ti.item_id WHERE l.id < :id "
					+ "UNION SELECT l.id, log_time, log_message, l.item_id AS item_id, ti.launch_id AS launch_id, project_id FROM log l "
					+ "JOIN test_item ti ON l.item_id = ti.item_id WHERE retry_of IS NOT NULL AND retry_of IN (SELECT item_id FROM test_item "
					+ "WHERE l.id < :id) ORDER BY id DESC LIMIT :maxLogNumber";

	public ElasticMigrationServiceImpl(JdbcTemplate jdbcTemplate, SimpleElasticSearchClient simpleElasticSearchClient,
			@Value("${rp.migration.elastic.startDate}") String startDate,
			@Value("${rp.migration.elastic.logsNumber}") String maxLogsNumberString) {
		this.jdbcTemplate = jdbcTemplate;
		int maxLogsNumberValue = StringUtils.hasText(maxLogsNumberString) ? Integer.parseInt(maxLogsNumberString) : 0;
		this.maxLogNumber = maxLogsNumberValue != 0 ? maxLogsNumberValue : DEFAULT_MAX_LOGS_NUMBER;
		this.namedParameterJdbcTemplate = new NamedParameterJdbcTemplate(jdbcTemplate);
		this.elasticSearchClient = simpleElasticSearchClient;
		if (startDate != null && !startDate.isEmpty()) {
			startDateTime = LocalDateTime.parse(startDate, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
		} else {
			startDateTime = null;
		}
	}

	@Override
	public void migrateLogs() {
		Long databaseFirstLogId = jdbcTemplate.queryForObject(SELECT_FIRST_ID, Long.class);
		if (databaseFirstLogId == null) {
			return;
		}
		if (startDateTime != null) {
			Long closestLogId;
			try {
				closestLogId = namedParameterJdbcTemplate.queryForObject(SELECT_LOG_ID_CLOSEST_TO_TIME,
						Map.of("time", startDateTime),
						Long.class
				);
			} catch (EmptyResultDataAccessException e){
				closestLogId = (long) Integer.MAX_VALUE;
			}
			compareIdsAndMigrate(databaseFirstLogId, closestLogId);
		} else {
			Optional<LogMessage> firstLogFromElastic = elasticSearchClient.getFirstLogFromElasticSearch();
			if (firstLogFromElastic.isEmpty()) {
				migrateAllLogs();
				return;
			}
			Long firstLogFromElasticId = firstLogFromElastic.get().getId();
			compareIdsAndMigrate(databaseFirstLogId, firstLogFromElasticId);
		}
	}

	private void compareIdsAndMigrate(Long databaseFirstLogId, Long startLogId) {
		int comparisonResult = databaseFirstLogId.compareTo(startLogId);
		if (comparisonResult == 0) {
			LOGGER.info("Elastic has the same logs as Postgres");
		} else if (comparisonResult < 0) {
			Long lastMigratedLogId = null;

			do {
				lastMigratedLogId = migrateLogsBeforeId(Objects.requireNonNullElse(lastMigratedLogId, startLogId));
			} while (!lastMigratedLogId.equals(Long.MAX_VALUE));
			LOGGER.info("Migration completed at {}", LocalDateTime.now());
		}
	}

	private void migrateAllLogs() {
		LOGGER.info("Migrating all logs from Postgres");

		List<LogMessage> logMessageWithLaunchIdList;
		List<LogMessage> logMessageWithoutLaunchIdList;
		Long lastMigratedLogId = null;
		do {
			if (lastMigratedLogId != null) {
				do {
					lastMigratedLogId = migrateLogsBeforeId(lastMigratedLogId);
				} while (!lastMigratedLogId.equals(Long.MAX_VALUE));
			} else {
				logMessageWithLaunchIdList = namedParameterJdbcTemplate.query(SELECT_ALL_LOGS_WITH_LAUNCH_ID,
						Map.of("maxLogNumber", maxLogNumber),
						new LogRowMapper()
				);
				logMessageWithoutLaunchIdList = namedParameterJdbcTemplate.query(SELECT_ALL_LOGS_WITHOUT_LAUNCH_ID,
						Map.of("maxLogNumber", maxLogNumber),
						new LogRowMapper()
				);
				elasticSearchClient.save(groupLogsByProject(logMessageWithLaunchIdList, logMessageWithoutLaunchIdList));

				lastMigratedLogId = getLastMigratedLogId(logMessageWithLaunchIdList, logMessageWithoutLaunchIdList);
			}

		} while (!lastMigratedLogId.equals(Long.MAX_VALUE));
		LOGGER.info("Migration completed at {}", LocalDateTime.now());
	}

	private Long migrateLogsBeforeId(Long id) {
		List<LogMessage> logMessageWithLaunchIdList = namedParameterJdbcTemplate.query(SELECT_LOGS_WITH_LAUNCH_ID_BEFORE_ID,
				Map.of("id", id, "maxLogNumber", maxLogNumber),
				new LogRowMapper()
		);
		List<LogMessage> logMessageWithoutLaunchIdList = namedParameterJdbcTemplate.query(SELECT_LOGS_WITHOUT_LAUNCH_ID_BEFORE_ID,
				Map.of("id", id, "maxLogNumber", maxLogNumber),
				new LogRowMapper()
		);
		elasticSearchClient.save(groupLogsByProject(logMessageWithLaunchIdList, logMessageWithoutLaunchIdList));

		return getLastMigratedLogId(logMessageWithLaunchIdList, logMessageWithoutLaunchIdList);
	}

	private TreeMap<Long, List<LogMessage>> groupLogsByProject(List<LogMessage> logsWithLaunchId, List<LogMessage> logsWithoutLaunchId) {
		TreeMap<Long, List<LogMessage>> projectMap = new TreeMap<>();
		for (LogMessage logMessage : logsWithLaunchId) {
			List<LogMessage> logMessageList = new ArrayList<>();
			logMessageList.add(logMessage);
			projectMap.put(logMessage.getProjectId(), logMessageList);
		}
		for (LogMessage logMessage : logsWithoutLaunchId) {
			if (projectMap.containsKey(logMessage.getProjectId())) {
				projectMap.get(logMessage.getProjectId()).add(logMessage);
			} else {
				List<LogMessage> logMessageList = new ArrayList<>();
				logMessageList.add(logMessage);
				projectMap.put(logMessage.getProjectId(), logMessageList);
			}
		}
		return projectMap;
	}

	private Long getLastMigratedLogId(List<LogMessage> logMessagesWithLaunchId, List<LogMessage> logMessagesWithoutLaunchId) {

		Long logWithLaunchId;
		Long logWithoutLaunchId;
		if (!logMessagesWithLaunchId.isEmpty()) {
			logWithLaunchId = logMessagesWithLaunchId.get(logMessagesWithLaunchId.size() - 1).getId();
		} else {
			logWithLaunchId = Long.MAX_VALUE;
		}
		if (!logMessagesWithoutLaunchId.isEmpty()) {
			logWithoutLaunchId = logMessagesWithoutLaunchId.get(logMessagesWithoutLaunchId.size() - 1).getId();
		} else {
			logWithoutLaunchId = Long.MAX_VALUE;
		}

		return logWithLaunchId.compareTo(logWithoutLaunchId) < 0 ? logWithLaunchId : logWithoutLaunchId;
	}
}
