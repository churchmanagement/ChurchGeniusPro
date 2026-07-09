#!/usr/bin/env bash
# Database query analysis. Runs the integration tests with Hibernate statistics
# enabled (spring.jpa.properties.hibernate.generate_statistics=true) and surfaces
# query counts so N+1 patterns and slow statements are visible. For production,
# enable pg_stat_statements and review target/site/jacoco + the Hibernate stats log.
set -euo pipefail
cd "$(dirname "$0")/.."
echo "==> Running integration tests with Hibernate query statistics"
./mvnw -B -ntp -Pcoverage verify \
  -Dspring.jpa.properties.hibernate.generate_statistics=true \
  -Dlogging.level.org.hibernate.stat=DEBUG \
  -Dlogging.level.org.hibernate.SQL=DEBUG 2>&1 | tee target/db-query-analysis.log || true
echo "==> Query summary (statements executed):"
grep -E "Session Metrics|statements executed|queries executed to database" target/db-query-analysis.log || \
  echo "    (run integration tests with Docker to populate query stats)"
