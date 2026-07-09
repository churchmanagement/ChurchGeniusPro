package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.ExpenseCheckImage;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ExpenseCheckImageRepository extends JpaRepository<ExpenseCheckImage, Long> {

    List<ExpenseCheckImage> findByExpenseIdAndClientIdOrderByIdDesc(Integer expenseId, String clientId);
}
