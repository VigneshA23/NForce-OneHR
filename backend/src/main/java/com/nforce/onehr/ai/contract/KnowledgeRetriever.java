package com.nforce.onehr.ai.contract;

import java.util.List;

/**
 * Authorised semantic retrieval over the OneHR knowledge base.
 *
 * <p>Contract: every returned result is already visible to the audiences on the query. An empty
 * list is a normal, expected outcome meaning "no authorised knowledge matched" — callers must
 * treat it as the UNKNOWN path and must not fall back to calling the model without context.
 */
public interface KnowledgeRetriever {

    List<RetrievalResult> retrieve(RetrievalQuery query);
}
