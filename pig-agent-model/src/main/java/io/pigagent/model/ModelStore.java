package io.pigagent.model;

import java.util.List;
import java.util.Optional;

/** Persistence for the global set of model configurations and the default-model pointer. */
public interface ModelStore {

    List<StoredModel> findAll();

    Optional<StoredModel> findById(String id);

    StoredModel save(StoredModel model);

    void deleteById(String id);

    /** Id of the global default model, or {@code null} if none set. */
    String getDefaultId();

    void setDefaultId(String id);
}
