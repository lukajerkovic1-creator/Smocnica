// Existing variants need no preliminary metadata write before replacing a photo.
// A denied upload must not consume the editor's base revision.
export async function saveVariantEdit(api, variant, revision, image) {
  const updated = image ? {
    ...variant,
    photoUri: await api.upload(variant.id, image),
    photoSource: "GALLERY",
  } : variant;
  return api.mutate("upsert_variant", variant.id, { variant: updated }, revision, "VARIANT");
}
