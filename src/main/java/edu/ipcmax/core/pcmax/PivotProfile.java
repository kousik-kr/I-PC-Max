package edu.ipcmax.core.pcmax;

import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.profile.TimeProfile;

/**
 * Pivot profile over a valid root domain.
 */
public record PivotProfile(int offset, Domain domain, TimeProfile profile) {
}
