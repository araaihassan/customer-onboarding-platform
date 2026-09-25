package co.ara.onboarding.agreement;

import org.springframework.stereotype.Component;

/**
 * Shared helpers for the agreement write services Task 12 onward introduce
 * (submit, review, send, sign, cancel) -- deliberately not a module-wide base
 * class, the same "small shared component, not an abstract superclass" shape
 * {@code AgreementInstantiation}/{@code AgreementFiles} already use elsewhere
 * in this module. Package-private: every caller lives in {@code agreement}
 * itself. Empty for now; Task 12 fills in the two helpers every later write
 * needs (bumping {@code lockVersion}-carrying fields such as {@code
 * lastEditedBy}/{@code updatedAt} together, and recording the version-number
 * bookkeeping {@link AgreementVersionRepository#maxVersionNumber} implies)
 * rather than each write service re-deriving them.
 */
@Component
class AgreementWrites {
}
