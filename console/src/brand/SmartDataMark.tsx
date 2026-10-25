import { useId, type SVGProps } from "react";

/** Original folded-strata mark: three observations become a shared passage. */
export function SmartDataMark({
  title,
  ...props
}: SVGProps<SVGSVGElement> & { title?: string }) {
  const titleId = useId();
  return (
    <svg
      xmlns="http://www.w3.org/2000/svg"
      viewBox="0 0 32 32"
      fill="none"
      role={title ? "img" : undefined}
      aria-hidden={title ? undefined : true}
      aria-labelledby={title ? titleId : undefined}
      {...props}
    >
      {title && <title id={titleId}>{title}</title>}
      <path
        d="M5 6h22v5H13l-8 8V6Z"
        fill="currentColor"
      />
      <path
        d="M27 26H5v-5h14l8-8v13Z"
        fill="currentColor"
      />
      <path
        d="m10 16 6-6h6l-6 6h-6Zm0 6 6-6h6l-6 6h-6Z"
        fill="currentColor"
        opacity=".52"
      />
    </svg>
  );
}

export default SmartDataMark;
