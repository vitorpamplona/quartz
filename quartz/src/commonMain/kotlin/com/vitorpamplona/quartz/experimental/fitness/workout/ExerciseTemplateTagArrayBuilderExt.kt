/*
 * Copyright (c) 2025 Vitor Pamplona
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to use,
 * copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the
 * Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN
 * AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
 * WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package com.vitorpamplona.quartz.experimental.fitness.workout

import com.vitorpamplona.quartz.experimental.fitness.workout.tags.DifficultyTag
import com.vitorpamplona.quartz.experimental.fitness.workout.tags.EquipmentTag
import com.vitorpamplona.quartz.experimental.fitness.workout.tags.FormatTag
import com.vitorpamplona.quartz.experimental.fitness.workout.tags.FormatUnitsTag
import com.vitorpamplona.quartz.experimental.fitness.workout.tags.TitleTag
import com.vitorpamplona.quartz.nip01Core.core.TagArrayBuilder

// Kind 33401 builders. They live apart from the kind 1301 and 33402 ones because several share a
// name (title, difficulty) and the files would otherwise compile to one JVM class.

fun TagArrayBuilder<ExerciseTemplateEvent>.title(title: String) = addUnique(TitleTag.assemble(title))

/** The fields each set records, in order (`weight`, `reps`, `rpe`, `set_type`, …). */
fun TagArrayBuilder<ExerciseTemplateEvent>.format(fields: List<String>) = addUnique(FormatTag.assemble(fields))

/** One unit per [format] field, in the same order (`kg`, `count`, `0-10`, …). */
fun TagArrayBuilder<ExerciseTemplateEvent>.formatUnits(units: List<String>) = addUnique(FormatUnitsTag.assemble(units))

fun TagArrayBuilder<ExerciseTemplateEvent>.equipment(equipment: String) = addUnique(EquipmentTag.assemble(equipment))

fun TagArrayBuilder<ExerciseTemplateEvent>.difficulty(difficulty: String) = addUnique(DifficultyTag.assemble(difficulty))
