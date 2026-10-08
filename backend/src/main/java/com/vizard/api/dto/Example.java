package com.vizard.api.dto;

/**
 * An example program from GET /api/examples.
 *
 * @param group     "Basics", "Searching" or "Sorting" (shown as groups in the menu)
 * @param stdin     input to pre-fill, or null
 * @param algorithm id of the algorithm Vizard should recognise in it, or null
 */
public record Example(String id, String title, String group, String code, String stdin, String algorithm) {
}
