package co.ara.onboarding.document;

import org.apache.tika.Tika;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * Task 7's hardening ruling (spec §2.3/§7.6), the sniffed-content half:
 * detects a document's real MIME type from its bytes -- {@link Tika#detect(byte[])}
 * runs magic-byte/structural detection over the array alone, with no filename
 * or declared Content-Type involved -- and refuses anything outside a small
 * allowlist per {@link DocumentCategory}. {@code Tika} is documented
 * thread-safe, so one instance is shared; this class carries no other state.
 *
 * The allowlist is deliberately per-category rather than one flat set for the
 * whole module: a CERTIFICATE or piece of KYC evidence is realistically a
 * scan (PDF or image), never a spreadsheet, and drawing that line costs
 * nothing given the category is already declared at upload time. OTHER is the
 * broadest bucket, matching its own "doesn't fit anywhere more specific"
 * meaning. None of the buckets include {@code text/html} or an executable
 * type -- which is what actually closes the brief's own example (an HTML
 * payload named {@code contract.pdf}): it sniffs as {@code text/html}, which
 * is on no category's list, rather than being compared against the filename
 * or declared type at all.
 *
 * <p><b>Verified against the actual {@code tika-core} jar, not assumed:</b> the
 * concrete OOXML types ({@link #DOCX}/{@link #XLSX}) have NO {@code <magic>}
 * entry in {@code tika-mimetypes.xml} at all -- only a filename glob and
 * {@code sub-class-of application/x-tika-ooxml}, and telling them apart from
 * their internal parts needs {@code ZipContainerDetector}, which lives in
 * {@code tika-parsers-standard}, a dependency this module deliberately does
 * not carry (see the class importing this one). {@code Tika.detect(byte[])}
 * on a real {@code .docx}/{@code .xlsx} therefore sniffs as {@link #OOXML}
 * ({@code application/x-tika-ooxml}) -- confirmed by constructing real
 * minimal OOXML packages (a valid ZIP with {@code [Content_Types].xml} as the
 * first entry, exactly what Word/Excel/every real OOXML writer produces) and
 * running them through the actual dependency. {@link #OOXML}'s own magic rule
 * requires {@code [Content_Types].xml} or {@code _rels/.rels} to be the
 * FIRST zip entry (Tika's own comment: "Only works if the Content Types or
 * rels file is the first zip entry") -- true of every real producer, so this
 * is not a loophole opened for a differently-shaped ZIP. Bare
 * {@code application/zip} (what an UNRELATED zip sniffs as) is deliberately
 * NOT added: doing so would accept any renamed archive, defeating the
 * allowlist's purpose. {@link #DOC}/{@link #XLS} (legacy OLE2) need no such
 * workaround -- their own magic rules match real byte content directly
 * (the OLE2 signature plus the real internal stream name, "WordDocument"/
 * "Workbook", Word/Excel actually write into the compound-file directory
 * sector) -- also confirmed against the real jar, not assumed.
 */
@Component
class ContentSniffGuard {

    private static final String PDF = "application/pdf";
    private static final String DOC = "application/msword";
    private static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private static final String XLS = "application/vnd.ms-excel";
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    /** What a real .docx/.xlsx (or any other OOXML package) actually sniffs as under tika-core alone -- see class javadoc. */
    private static final String OOXML = "application/x-tika-ooxml";
    private static final String JPEG = "image/jpeg";
    private static final String PNG = "image/png";
    private static final String TXT = "text/plain";
    private static final String CSV = "text/csv";

    /**
     * Contracts, agreements and NDAs are authored documents, never scans.
     * {@link #DOCX} itself is kept alongside {@link #OOXML} for forward
     * compatibility only -- if this module ever gains {@code tika-parsers}
     * and its {@code ZipContainerDetector}, sniffing would start returning
     * the concrete type instead, and no allowlist edit would be needed then
     * either. Today, only {@link #OOXML} is actually reachable for a real
     * {@code .docx}.
     */
    private static final Set<String> OFFICE_DOCS = Set.of(PDF, DOC, DOCX, OOXML);

    /** Registration certificates, tax and KYC evidence, certificates: realistically a scan. */
    private static final Set<String> SCANS = Set.of(PDF, JPEG, PNG);

    /** Invoices and technical documents: office formats or a scan, either is normal. */
    private static final Set<String> OFFICE_OR_SCAN = Set.of(PDF, DOC, DOCX, XLS, XLSX, OOXML, JPEG, PNG, TXT);

    /** OTHER: the broadest bucket, matching its own catch-all meaning -- still no HTML/executables. */
    private static final Set<String> EVERYTHING = Set.of(PDF, DOC, DOCX, XLS, XLSX, OOXML, JPEG, PNG, TXT, CSV);

    private static final Map<DocumentCategory, Set<String>> ALLOWED = new EnumMap<>(DocumentCategory.class);
    static {
        ALLOWED.put(DocumentCategory.CONTRACT, OFFICE_DOCS);
        ALLOWED.put(DocumentCategory.AGREEMENT, OFFICE_DOCS);
        ALLOWED.put(DocumentCategory.NDA, OFFICE_DOCS);
        ALLOWED.put(DocumentCategory.COMPANY_REGISTRATION, SCANS);
        ALLOWED.put(DocumentCategory.TAX, SCANS);
        ALLOWED.put(DocumentCategory.KYC, SCANS);
        ALLOWED.put(DocumentCategory.CERTIFICATE, SCANS);
        ALLOWED.put(DocumentCategory.INVOICE, OFFICE_OR_SCAN);
        ALLOWED.put(DocumentCategory.TECHNICAL, OFFICE_OR_SCAN);
        ALLOWED.put(DocumentCategory.OTHER, EVERYTHING);
    }

    private final Tika tika = new Tika();

    /** Sniffs the real content type from the bytes themselves -- never a filename or declared header. */
    String detect(byte[] prefix) {
        return tika.detect(prefix);
    }

    /** @throws UnacceptableContentTypeException when sniffedType is not on category's allowlist. */
    void enforce(DocumentCategory category, String sniffedType) {
        Set<String> allowed = ALLOWED.get(category);
        if (allowed == null || !allowed.contains(sniffedType)) {
            throw new UnacceptableContentTypeException(category, sniffedType);
        }
    }
}
