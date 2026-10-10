package com.vizard.execution.trace;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** How the trace classifies a user object from its links to its own class. */
class NodeShapeTest {

    @Test
    void shapes() {
        assertThat(HeapReader.nodeShape(List.of("left", "right")).role()).isEqualTo("tree-node");
        assertThat(HeapReader.nodeShape(List.of("next")).role()).isEqualTo("list-node");
        assertThat(HeapReader.nodeShape(List.of("prev", "next")).links()).containsExactly("next", "prev");
        assertThat(HeapReader.nodeShape(List.of("first", "second")).role()).isEqualTo("tree-node");
        assertThat(HeapReader.nodeShape(List.of("left", "right", "parent")).links()).containsExactly("left", "right");
        assertThat(HeapReader.nodeShape(List.of())).isNull();
        assertThat(HeapReader.nodeShape(List.of("a", "b", "c"))).isNull();
    }
}
