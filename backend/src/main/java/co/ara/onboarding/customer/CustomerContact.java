package co.ara.onboarding.customer;

import co.ara.onboarding.tenancy.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * A person at a customer organisation.
 *
 * A contact is a business record in its own right and exists whether or not it
 * ever gets a login, which is why userId is nullable — it stays null until the
 * portal invitation is accepted (spec 9.1, QA Q12).
 *
 * The field is named primaryContact rather than primary because `primary` reads
 * poorly as a Java identifier and `isPrimary()` sits awkwardly beside JPA
 * conventions. The column is primary_contact either way.
 */
@Entity
@Table(name = "customer_contact")
public class CustomerContact extends TenantScopedEntity {

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Column(nullable = false)
    private String email;

    @Column private String title;

    @Column private String phone;

    @Column(name = "primary_contact", nullable = false)
    private boolean primaryContact = false;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ContactStatus status;

    public UUID getCustomerId() { return customerId; }
    public void setCustomerId(UUID customerId) { this.customerId = customerId; }

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }

    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }

    public boolean isPrimaryContact() { return primaryContact; }
    public void setPrimaryContact(boolean primaryContact) { this.primaryContact = primaryContact; }

    public ContactStatus getStatus() { return status; }
    public void setStatus(ContactStatus status) { this.status = status; }
}
