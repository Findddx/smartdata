import type { LineageFieldPage } from "./types";

export function boundFieldPage(
  page: LineageFieldPage | undefined,
  stateVersion: number,
  sourceId: string,
  assetId: string,
  offset: number,
) {
  if (
    !page ||
    page.stateVersion !== stateVersion ||
    page.sourceId !== sourceId ||
    page.assetId !== assetId ||
    page.offset !== offset ||
    page.limit < 1 ||
    page.limit > 128 ||
    page.nodes.length > page.limit
  )
    return undefined;
  if (
    new Set(page.nodes.map((node) => node.id)).size !== page.nodes.length ||
    page.nodes.some(
      (node) =>
        node.layer !== "L0" ||
        node.kind !== "FIELD" ||
        node.ref.sourceId !== sourceId ||
        node.ref.assetId !== assetId ||
        !node.ref.fieldName ||
        node.authorization !== "METADATA_VISIBLE",
    )
  )
    return undefined;
  return page;
}
