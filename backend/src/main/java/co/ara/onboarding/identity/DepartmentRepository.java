package co.ara.onboarding.identity;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface DepartmentRepository extends JpaRepository<Department, UUID> {}
