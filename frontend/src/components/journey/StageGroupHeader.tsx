/**
 * The stage name above its milestone rows (uispecs §5a). `Roadmap` decides
 * *whether* to render this -- suppressed when a stage holds exactly one
 * milestone of the same name, so a 1:1 workflow shows nine milestone rows
 * rather than nine headers each holding one row.
 */
export function StageGroupHeader({ name }: { name: string }) {
  return (
    <h3
      className="text-text-faint"
      style={{
        font: "500 var(--ob-type-10-size)/var(--ob-type-10-line) var(--ob-font-family-data)",
        textTransform: "uppercase",
        letterSpacing: "0.08em",
      }}
    >
      {name}
    </h3>
  );
}
