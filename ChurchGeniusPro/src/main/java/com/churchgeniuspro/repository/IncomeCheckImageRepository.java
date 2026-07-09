package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.IncomeCheckImage;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface IncomeCheckImageRepository extends JpaRepository<IncomeCheckImage, Long> {

    List<IncomeCheckImage> findByIncomeIdAndClientIdOrderByIdDesc(Integer incomeId, String clientId);
}
