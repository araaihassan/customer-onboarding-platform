import en from "./messages/en.json";

const messages: Record<string, string> = en;

/**
 * English only at launch, but every user-facing string goes through here from day
 * one — PRD §16 requires multi-language readiness, and retrofitting i18n across
 * nine modules is a punishing refactor.
 *
 * A missing key returns the key itself rather than throwing or rendering empty.
 * That makes a gap visible in the interface — "customer.list.title" on screen is
 * unmistakable — instead of failing a page or silently showing nothing.
 */
export function t(key: string, params?: Record<string, string>): string {
  const template = messages[key];
  if (template === undefined) return key;
  if (!params) return template;

  // Only supplied parameters are substituted. An unsupplied placeholder stays as
  // "{name}", which is a legible bug; String(undefined) would print "undefined"
  // to the user and read like real copy.
  return Object.entries(params).reduce(
    (acc, [name, value]) => acc.replaceAll(`{${name}}`, value),
    template,
  );
}
