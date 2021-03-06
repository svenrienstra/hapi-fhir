package org.hl7.fhir.instance.model;

/*
 * #%L
 * HAPI FHIR - Core Library
 * %%
 * Copyright (C) 2014 - 2015 University Health Network
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */


public class ResourceEnumerations {

  @SuppressWarnings("rawtypes")
  public static EnumFactory getEnumFactory(Class<? extends Enum> clss) {
    if (clss == HumanName.NameUse.class)
      return new HumanName.NameUseEnumFactory();
    if (clss == Observation.ObservationReliability.class)
      return new Observation.ObservationReliabilityEnumFactory();
    if (clss == Observation.ObservationStatus.class)
      return new Observation.ObservationStatusEnumFactory();
    return null;
  }

}
