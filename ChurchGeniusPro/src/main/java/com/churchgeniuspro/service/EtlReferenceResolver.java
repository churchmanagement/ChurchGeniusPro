package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.MainSource;
import com.churchgeniuspro.hibernate.Purpose;
import com.churchgeniuspro.hibernate.SubSource;
import com.churchgeniuspro.repository.MainSourceRepository;
import com.churchgeniuspro.repository.PurposeRepository;
import com.churchgeniuspro.repository.SubSourceRepository;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tenant-scoped get-or-create for the income/expense reference rows the live
 * tables require ({@code main_source}, {@code sub_source}, {@code purpose}). Used
 * by the ETL loader so an imported "Tithes" fund or "Utilities" purpose maps to a
 * real FK — creating the reference row once if it does not exist for the tenant.
 *
 * <p>Use {@link #session(String)} to get a per-load {@link Session} that caches
 * lookups (so a 5,000-row import does one read per distinct name, not per row).
 * Every created reference row is stamped with the tenant's {@code appClientId}.
 */
@Service
public class EtlReferenceResolver {

    private final MainSourceRepository mainSourceRepo;
    private final SubSourceRepository  subSourceRepo;
    private final PurposeRepository    purposeRepo;

    public EtlReferenceResolver(MainSourceRepository mainSourceRepo,
                                SubSourceRepository subSourceRepo,
                                PurposeRepository purposeRepo) {
        this.mainSourceRepo = mainSourceRepo;
        this.subSourceRepo  = subSourceRepo;
        this.purposeRepo    = purposeRepo;
    }

    public Session session(String tenant) {
        return new Session(tenant);
    }

    private static String key(String s) {
        return s == null ? "" : s.trim().toLowerCase();
    }

    /** Per-load cache + creator. Not thread-safe; create one per load operation. */
    public class Session {
        private final String tenant;
        private final Map<String, MainSource> mainByName = new HashMap<>();
        private final Map<String, SubSource>  subByKey   = new HashMap<>();   // mainId|subName
        private final Map<String, Purpose>    purposeByName = new HashMap<>();
        private boolean mainLoaded, subLoaded, purposeLoaded;

        Session(String tenant) { this.tenant = tenant; }

        public MainSource mainSource(String name) {
            String n = (name == null || name.isBlank()) ? "Imported" : name.trim();
            if (!mainLoaded) {
                for (MainSource m : safe(mainSourceRepo.findActiveByAppUser(tenant)))
                    mainByName.putIfAbsent(key(m.getSourceName()), m);
                mainLoaded = true;
            }
            MainSource hit = mainByName.get(key(n));
            if (hit != null) return hit;
            MainSource created = new MainSource();
            created.setSourceName(n);
            created.setAppClientId(tenant);
            created.setDeleteFlag(false);
            created.setCreatedDate(new Date());
            created = mainSourceRepo.save(created);
            mainByName.put(key(n), created);
            return created;
        }

        public SubSource subSource(String mainName, String subName) {
            MainSource main = mainSource(mainName);
            String sn = (subName == null || subName.isBlank())
                ? (mainName == null || mainName.isBlank() ? "Imported" : mainName.trim())
                : subName.trim();
            if (!subLoaded) {
                for (SubSource s : safe(subSourceRepo.findAllActiveByAppUser(tenant))) {
                    Integer mid = s.getMainSource() == null ? null : s.getMainSource().getId();
                    subByKey.putIfAbsent(mid + "|" + key(s.getSourceName()), s);
                }
                subLoaded = true;
            }
            String k = main.getId() + "|" + key(sn);
            SubSource hit = subByKey.get(k);
            if (hit != null) return hit;
            SubSource created = new SubSource();
            created.setSourceName(sn);
            created.setMainSource(main);
            created.setAppClientId(tenant);
            created.setDeleteFlag(false);
            created.setCreatedDate(new Date());
            created = subSourceRepo.save(created);
            subByKey.put(k, created);
            return created;
        }

        public Purpose purpose(String name) {
            String n = (name == null || name.isBlank()) ? "Imported" : name.trim();
            if (!purposeLoaded) {
                for (Purpose p : safe(purposeRepo.findActiveByAppUser(tenant)))
                    purposeByName.putIfAbsent(key(p.getPurposeName()), p);
                purposeLoaded = true;
            }
            Purpose hit = purposeByName.get(key(n));
            if (hit != null) return hit;
            Purpose created = new Purpose();
            created.setPurposeName(n);
            created.setAppClientId(tenant);
            created.setDeleteFlag(false);
            created.setCreatedDate(new Date());
            created = purposeRepo.save(created);
            purposeByName.put(key(n), created);
            return created;
        }

        private <T> List<T> safe(List<T> l) { return l == null ? List.of() : l; }
    }
}
