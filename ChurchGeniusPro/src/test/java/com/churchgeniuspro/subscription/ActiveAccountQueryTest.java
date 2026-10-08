package com.churchgeniuspro.subscription;

import com.churchgeniuspro.hibernate.ServiceClient;
import com.churchgeniuspro.repository.ServiceClientRepository;
import jakarta.persistence.EntityManager;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the real JPQL behind {@code findActiveAccountClientIds} against an in-memory
 * database. Proves it parses (a bad @Query stops the application from starting) and
 * that it applies the sign-in rule exactly: Active, not deleted, end_date strictly
 * after today.
 */
@DisplayName("findActiveAccountClientIds — JPQL against a real schema")
class ActiveAccountQueryTest {

    static SessionFactory sf;

    @BeforeAll
    static void boot() {
        sf = new Configuration()
                .addAnnotatedClass(ServiceClient.class)
                .setProperty("hibernate.connection.url", "jdbc:h2:mem:activeacct;DB_CLOSE_DELAY=-1")
                .setProperty("hibernate.connection.username", "sa")
                .setProperty("hibernate.hbm2ddl.auto", "create-drop")
                .buildSessionFactory();
    }

    @AfterAll
    static void close() { if (sf != null) sf.close(); }

    static String jpql() throws Exception {
        return ServiceClientRepository.class.getMethod("findActiveAccountClientIds")
                .getAnnotation(Query.class).value();
    }

    void add(EntityManager em, String cid, String status, LocalDate end, boolean deleted) {
        ServiceClient c = new ServiceClient();
        c.setClientId(cid); c.setName("n"); c.setChurchName("c"); c.setEmail("e@x.org");
        c.setStatus(status); c.setEndDate(end); c.setDeleteFlag(deleted);
        c.setStartDate(LocalDate.now().minusDays(10));
        em.persist(c);
    }

    @Test
    @DisplayName("only Active, undeleted accounts ending after today are returned")
    void predicate() throws Exception {
        LocalDate today = LocalDate.now();
        EntityManager em = sf.createEntityManager();
        em.getTransaction().begin();
        add(em, "ok",        "Active",   today.plusDays(1),  false);
        add(em, "today",     "Active",   today,              false);
        add(em, "past",      "Active",   today.minusDays(3), false);
        add(em, "hold",      "Hold",     today.plusDays(30), false);
        add(em, "inactive",  "Inactive", today.plusDays(30), false);
        add(em, "deleted",   "Active",   today.plusDays(30), true);
        add(em, "noend",     "Active",   null,               false);
        em.getTransaction().commit();

        List<String> ids = em.createQuery(jpql(), String.class).getResultList();
        em.close();
        assertThat(ids).containsExactly("ok");
    }
}
