package com.vizard.api.dto;

import java.util.List;

/**
 * An example program from GET /api/examples.
 *
 * @param group     "Basics", "Searching", "Sorting" or "Data structures" (shown as groups in the menu)
 * @param stdin     input to pre-fill, or null
 * @param algorithms ids of the algorithms Vizard should recognise in it, in source order (may be empty)
 */
public record Example(String id, String title, String group, String code, String stdin, List<String> algorithms) {
}
