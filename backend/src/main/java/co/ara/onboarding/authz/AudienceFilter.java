package co.ara.onboarding.authz;

import org.springframework.data.jpa.domain.Specification;

/**
 * Declares, for one entity type, a MANDATORY predicate that narrows reads
 * regardless of the scope the actor holds the permission at -- including ALL.
 *
 * This exists because record scope and record audience are different questions.
 * Scope asks "which records of this type may this actor touch"; audience asks
 * "for this particular record, is this actor among its intended readers".
 * AuthorizationPredicateBuilder short-circuits scope to cb.conjunction() as soon
 * as the actor holds ALL, so an audience expressed as a descriptor predicate is
 * skipped by exactly the actor it most needs to bind (design spec 6.1).
 *
 * DELIBERATELY NOT A DEFAULT METHOD ON ResourceAuthorizationDescriptor.
 * DescriptorRegistry.forEntity throws on a miss, and the builder's ALL branch
 * returns before ever calling it -- so a default method would make that lookup
 * unconditional and turn every currently-working ALL-scoped read of a
 * descriptor-less entity into an IllegalStateException, across four modules, for
 * no gain. An opt-in registry returning Optional.empty() contributes nothing
 * where nothing is declared, leaves the nineteen existing descriptors untouched,
 * and makes the complete list of audience-governed types one grep for
 * "implements AudienceFilter".
 *
 * KEYED ON THE PERMISSION AS WELL AS THE ENTITY, and that is load-bearing rather
 * than incidental: document.manage must be able to load a document its holder
 * may not read, so that a mis-targeted document can be retargeted rather than
 * becoming permanently unreachable (spec 6.4). An entity-keyed filter would
 * apply to the read PATCH performs and make that impossible.
 *
 * Implementations live in `scoping`, like descriptors, and for the same reason:
 * placing one in the module owning the entity would close a module cycle.
 */
public interface AudienceFilter<T> {

    Class<T> entityType();

    /**
     * Must fail CLOSED on missing context, exactly as descriptors must: when the
     * AuthContext carries nothing to match on, return cb.disjunction(), never
     * cb.conjunction(). An audience that widens when its input is missing is
     * indistinguishable from no audience at all.
     */
    Specification<T> audience(AuthContext ctx, String permissionKey);
}
