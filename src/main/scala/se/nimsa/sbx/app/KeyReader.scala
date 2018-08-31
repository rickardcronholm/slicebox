/*
 * Copyright 2018 Rickard Cromholm
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package se.nimsa.sbx.app

import java.nio.file.{Files, NoSuchFileException, Paths}
import java.security.{KeyFactory, PrivateKey, PublicKey}
import java.security.spec.{PKCS8EncodedKeySpec, X509EncodedKeySpec}

object KeyReader {

  @throws(classOf[NoSuchFileException])
  def PrivateKeyReader(filename: String): PrivateKey = {
    val keyBytes = Files.readAllBytes(Paths.get(filename))
    val spec : PKCS8EncodedKeySpec = new PKCS8EncodedKeySpec(keyBytes)
    val keyFactory : KeyFactory = KeyFactory.getInstance("RSA")
    keyFactory.generatePrivate(spec)
  }

  @throws(classOf[NoSuchFileException])
  def PublicKeyReader(filename: String): PublicKey = {
    val keyBytes = Files.readAllBytes(Paths.get(filename))
    val spec : X509EncodedKeySpec = new X509EncodedKeySpec(keyBytes)
    val keyFactory : KeyFactory = KeyFactory.getInstance("RSA")
    keyFactory.generatePublic(spec)
  }
}