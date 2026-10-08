package com.churchgeniuspro.service;

import com.churchgeniuspro.config.SchemaDriftGuard;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.generator.Generator;
import org.hibernate.id.enhanced.SequenceStyleGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * Which database sequence feeds which entity's id column, read from the Hibernate
 * mapping rather than guessed from names.
 *
 * <p>121 entities take their ids from a {@code @SequenceGenerator}; PostgreSQL knows
 * nothing about that link (the column has no default — Hibernate calls {@code nextval}
 * itself), so after rows are loaded with explicit ids — a snapshot restore, an import —
 * the sequence still sits at its old value and the next insert fails on a duplicate key
 * (database audit C2). Two of those sequences do not follow the {@code <table>_id_seq}
 * convention ({@code member_message_seq}, {@code plaid_txn_staging_id_seq}), which is why
 * this asks the mapping instead of pattern-matching names. Identity columns are not
 * listed here: the catalogue owns those and {@link BackupService} finds them there.
 */
@Component
public class IdSequenceCatalog {

    private static final Logger log = LoggerFactory.getLogger(IdSequenceCatalog.class);

    private final EntityManagerFactory emf;
    private volatile Map<String, Map<String, String>> cache;

    public IdSequenceCatalog(EntityManagerFactory emf) {
        this.emf = emf;
    }

    /** table → (id column → sequence name), for every entity whose id comes from a sequence. */
    public Map<String, Map<String, String>> byTable() {
        Map<String, Map<String, String>> c = cache;
        if (c == null) {
            c = Collections.unmodifiableMap(read());
            cache = c;
        }
        return c;
    }

    private Map<String, Map<String, String>> read() {
        Map<String, Map<String, String>> out = new TreeMap<>();
        try {
            SessionFactoryImplementor sf = emf.unwrap(SessionFactoryImplementor.class);
            sf.getMappingMetamodel().forEachEntityDescriptor(persister -> {
                Generator generator = persister.getGenerator();
                if (!(generator instanceof SequenceStyleGenerator seq)) {
                    return;
                }
                String[] idColumns = persister.getIdentifierColumnNames();
                if (idColumns == null || idColumns.length != 1) {
                    return;
                }
                String table = SchemaDriftGuard.norm(persister.getMappedTableDetails().getTableName());
                String sequence = SchemaDriftGuard.norm(
                        seq.getDatabaseStructure().getPhysicalName().getObjectName().getText());
                out.computeIfAbsent(table, k -> new TreeMap<>())
                   .put(SchemaDriftGuard.norm(idColumns[0]), sequence);
            });
        } catch (Exception e) {
            log.warn("IdSequenceCatalog: could not read id sequences from the entity mapping — {}", e.getMessage());
        }
        return out;
    }
}
