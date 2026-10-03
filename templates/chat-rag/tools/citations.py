"""Shape retrieved hits into the citation objects ctx.final accepts."""

def citations_from(hits):
    citations = []
    for hit in hits or []:
        citations.append({
            "doc_id": hit.get("doc_id") or hit.get("docId"),
            "chunk_id": hit.get("chunk_id") or hit.get("chunkId"),
            "filename": hit.get("filename") or "",
            "score": hit.get("score") if hit.get("score") is not None else hit.get("finalScore"),
        })
    return citations


def answer_lists_sources(answer, citations):
    if not citations:
        return False
    return all(item.get("filename") and item["filename"] in answer for item in citations)
