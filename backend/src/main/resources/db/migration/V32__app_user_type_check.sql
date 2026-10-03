-- UserType.SYSTEM is the scheduler's in-memory actor and is never a stored value. Until now
-- nothing at the database refused it, and AuthorizationService once keyed system authority on
-- the looked-up user type. Only INTERNAL and PORTAL are ever written (TenantProvisioningService,
-- UserAdminService, ActivationService).
ALTER TABLE app_user ADD CONSTRAINT app_user_user_type_check CHECK (user_type IN ('INTERNAL', 'PORTAL'));
