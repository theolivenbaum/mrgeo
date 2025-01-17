/*
 * Copyright 2009-2016 DigitalGlobe, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 *
 */

package org.mrgeo.mapalgebra

import org.mrgeo.mapalgebra.parser.ParserNode
import org.mrgeo.mapalgebra.raster.RasterMapOp

object AspectMapOp extends MapOpRegistrar {
   override def register: Array[String] = {
     Array[String]("aspect")
   }

  def create(raster:RasterMapOp):MapOp = {
    new SlopeAspectMapOp(Some(raster), "rad", false)
  }

  def create(raster:RasterMapOp, units:String):MapOp = {
    new SlopeAspectMapOp(Some(raster), units, false)
  }

   override def apply(node:ParserNode, variables: String => Option[ParserNode]): MapOp =
     new SlopeAspectMapOp(node, false, variables)
 }

// Dummy class definition to allow the python reflection to find the Aspect mapop
abstract class AspectMapOp extends RasterMapOp {
}
