package com.osmm.capture;

import java.util.HashMap;
import java.util.Map;

/** The last values written for one actor, so only changes are recorded. */
final class ActorState
{
	// last mv line
	int lx = Integer.MIN_VALUE;
	int ly;
	int anim;
	int animFrame;
	int pose;
	int poseFrame;
	int curOri;

	int[] equipment;                 // raw PlayerComposition.getEquipmentIds()
	int[] npcOverheadArchives;
	short[] npcOverheadSprites;
	Map<Long, int[]> spots = new HashMap<>();   // slot -> {id, startCycle, height}
}
