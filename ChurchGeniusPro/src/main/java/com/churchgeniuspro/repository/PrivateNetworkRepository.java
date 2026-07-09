package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.PrivateNetwork;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PrivateNetworkRepository extends JpaRepository<PrivateNetwork, Long> {
    List<PrivateNetwork> findByClientIdOrderByNameAsc(String clientId);
    List<PrivateNetwork> findByClientIdAndEnabledTrue(String clientId);
}
