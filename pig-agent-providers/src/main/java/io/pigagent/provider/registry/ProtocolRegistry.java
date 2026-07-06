package io.pigagent.provider.registry;

import io.pigagent.core.protocol.ModelProtocol;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Registry of the model access protocols known to the CLI. */
public final class ProtocolRegistry {

    private final List<ModelProtocol> protocols = new ArrayList<>();

    public void register(ModelProtocol protocol) { protocols.add(protocol); }

    public Optional<ModelProtocol> findByProtocol(String protocolId) {
        return protocols.stream().filter(p -> p.protocolId().equals(protocolId)).findFirst();
    }

    public List<ModelProtocol> getAllProtocols() { return List.copyOf(protocols); }
}
