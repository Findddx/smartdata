import { useId, type ReactNode, type SVGProps } from "react";

export type SmartDataGlyphKind =
  | "SOURCE"
  | "TABLE"
  | "FIELD"
  | "BUSINESS_ENTITY"
  | "PROFILE"
  | "METRIC"
  | "DECISION_PLAN"
  | "EVIDENCE"
  | "TEAM"
  | "MAPPING";

// Reuse static geometry during pan/zoom; do not allocate all glyphs per node render.
const shapes = {
    SOURCE: <><ellipse cx="12" cy="5.5" rx="7" ry="3"/><path d="M5 5.5v12c0 1.7 3.1 3 7 3s7-1.3 7-3v-12M5 11.5c0 1.7 3.1 3 7 3s7-1.3 7-3"/></>,
    TABLE: <><rect x="3.5" y="4" width="17" height="16" rx="2"/><path d="M4 9h16M9 9v11M4 14.5h16"/></>,
    FIELD: <><path d="M8 4H5v16h3M16 4h3v16h-3M10 8h4M10 12h4M10 16h4"/></>,
    BUSINESS_ENTITY: <><path d="m12 3 8 4.5v9L12 21l-8-4.5v-9L12 3ZM4 7.5l8 4.5 8-4.5M12 12v9"/></>,
    PROFILE: <><circle cx="8" cy="7" r="3"/><path d="M2.5 20v-2.5a5.5 5.5 0 0 1 11 0V20h-11ZM16 6h5M16 11h5M17 16h4M17 20h4"/></>,
    METRIC: <><path d="M4 4v16h17M8 15l4-5 4 2 4-7"/><path d="M17 5h3v3"/></>,
    DECISION_PLAN: <><path d="m12 3 5 5-5 5-5-5 5-5ZM12 13v4M12 17H5v4M12 17h7v4"/><circle cx="5" cy="21" r="1"/><circle cx="19" cy="21" r="1"/></>,
    EVIDENCE: <><path d="M15 3H5v18h14V7l-4-4ZM15 3v5h4M8 12h8M8 16h5"/><path d="m15 17 1.5 1.5L20 15"/></>,
    TEAM: <><circle cx="12" cy="6" r="3"/><circle cx="4" cy="12" r="2"/><circle cx="20" cy="12" r="2"/><path d="M7 21v-3a5 5 0 0 1 10 0v3H7ZM1 21v-3a3 3 0 0 1 4-2.8M23 21v-3a3 3 0 0 0-4-2.8"/></>,
    MAPPING: <><rect x="2" y="3" width="7" height="6" rx="1"/><rect x="15" y="15" width="7" height="6" rx="1"/><path d="M5.5 9v6a3 3 0 0 0 3 3H15M12 3h7v7M16 7l3 3 3-3"/></>,
} satisfies Record<SmartDataGlyphKind, ReactNode>;

/** Object identity only. Layer, access and freshness have independent encodings. */
export function SmartDataGlyph({
  kind,
  size = 20,
  title,
  ...props
}: SVGProps<SVGSVGElement> & {
  kind: SmartDataGlyphKind;
  size?: number;
  title?: string;
}) {
  const titleId = useId();
  return (
    <svg
      xmlns="http://www.w3.org/2000/svg"
      viewBox="0 0 24 24"
      width={size}
      height={size}
      fill="none"
      stroke="currentColor"
      strokeWidth="1.5"
      strokeLinecap="round"
      strokeLinejoin="round"
      role={title ? "img" : undefined}
      aria-hidden={title ? undefined : true}
      aria-labelledby={title ? titleId : undefined}
      {...props}
    >
      {title && <title id={titleId}>{title}</title>}
      {shapes[kind]}
    </svg>
  );
}
