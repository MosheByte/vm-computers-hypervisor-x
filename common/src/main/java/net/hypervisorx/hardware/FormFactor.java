package net.hypervisorx.hardware;

import java.util.List;

/** Machine chassis: which slots it has and how they're laid out in the assembly screen. */
public enum FormFactor {
    RETRO("retro_pc", List.of(ComponentType.CPU, ComponentType.RAM, ComponentType.RAM, ComponentType.GPU,
            ComponentType.STORAGE, ComponentType.EXPANSION, ComponentType.USB)),
    TOWER("gaming_tower", List.of(ComponentType.CPU, ComponentType.RAM, ComponentType.RAM, ComponentType.RAM, ComponentType.RAM,
            ComponentType.GPU, ComponentType.STORAGE, ComponentType.STORAGE, ComponentType.EXPANSION, ComponentType.EXPANSION,
            ComponentType.USB, ComponentType.USB)),
    RACK("server_rack", List.of(ComponentType.BLADE, ComponentType.BLADE, ComponentType.STORAGE, ComponentType.STORAGE,
            ComponentType.EXPANSION));

    private final String id;
    private final List<ComponentType> slots;

    FormFactor(String id, List<ComponentType> slots) {
        this.id = id;
        this.slots = slots;
    }

    public String id() {
        return id;
    }

    public List<ComponentType> slots() {
        return slots;
    }

    public int slotCount() {
        return slots.size();
    }
}
