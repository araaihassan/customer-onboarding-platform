package co.ara.onboarding.customer;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * Thin: binds path variables and delegates. No authorization logic here — that is
 * the gate's job, and ModuleBoundaryTest stops this class reaching a repository.
 *
 * There is deliberately no DELETE mapping. Business records are deactivated, never
 * deleted (spec 9.4), and the database would refuse it anyway.
 */
@RestController
@RequestMapping("/api/t/{tenantSlug}/customers")
public class CustomerController {

    private final CustomerService customers;

    public CustomerController(CustomerService customers) { this.customers = customers; }

    @GetMapping
    public Page<CustomerService.CustomerView> list(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) CustomerStatus status,
            Pageable pageable) {
        return customers.list(search, status, pageable);
    }

    @GetMapping("/{id}")
    public CustomerService.CustomerView get(@PathVariable UUID id) { return customers.get(id); }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CustomerService.CustomerView create(
            @RequestBody CustomerService.CreateCustomerRequest request) {
        return customers.create(request);
    }

    @PutMapping("/{id}")
    public CustomerService.CustomerView update(
            @PathVariable UUID id,
            @RequestBody CustomerService.UpdateCustomerRequest request) {
        return customers.update(id, request);
    }

    @PostMapping("/{id}/deactivate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deactivate(@PathVariable UUID id,
                           @RequestBody(required = false) Map<String, String> body) {
        customers.deactivate(id, body == null ? null : body.get("reason"));
    }
}
