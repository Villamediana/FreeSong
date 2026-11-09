// Foto de perfil: a foto é pública (vai pra lista global), então sai daqui só a imagem.
//
// Percorre os segmentos do JPEG e joga fora o que não é pra desenhar: EXIF (GPS, modelo e número
// de série da câmera, data, miniatura da foto ORIGINAL sem corte), XMP, IPTC, comentários e o JFIF
// (que também pode trazer miniatura). Fica só o perfil de cor (APP2 ICC_PROFILE) e o Adobe (APP14,
// que diz como ler as cores). Tudo depois do fim da imagem (EOI) também sai: é onde se escondem
// arquivos "colados" num JPEG. JPEG malformado, sem quadro ou gigante (bomba de memória no celular
// de quem abrir a lista) é recusado.

/** Lado máximo aceito: o app manda 512 px. */
export const MAX_SIDE = 2048;

// Início de quadro (dimensões da imagem): C0–CF menos C4 (tabela Huffman), C8 (reservado) e CC.
const SOF = new Set([0xc0, 0xc1, 0xc2, 0xc3, 0xc5, 0xc6, 0xc7, 0xc9, 0xca, 0xcb, 0xcd, 0xce, 0xcf]);
const SOS = 0xda;
const EOI = 0xd9;

const startsWith = (buf, at, text) => buf.length >= at + text.length && buf.toString('latin1', at, at + text.length) === text;

/** Segmento de aplicação/comentário que fica? Só ICC e Adobe. */
function keepMeta(buf, i, marker) {
  if (marker === 0xe2) return startsWith(buf, i + 4, 'ICC_PROFILE\0');
  if (marker === 0xee) return startsWith(buf, i + 4, 'Adobe');
  return false;
}

/** A foto limpa (Buffer novo) ou null se não é um JPEG que serve. */
export function cleanJpeg(buf, maxSide = MAX_SIDE) {
  if (!Buffer.isBuffer(buf) || buf.length < 100 || buf[0] !== 0xff || buf[1] !== 0xd8) return null;
  const out = [buf.subarray(0, 2)];
  let i = 2;
  let frame = false;
  let scan = false;
  while (i < buf.length) {
    if (scan) {
      // Dados comprimidos: vão até o próximo marcador de verdade (FF 00 é byte escapado, FF D0–D7 é
      // reinício, FF FF é preenchimento).
      const start = i;
      for (;;) {
        if (i + 1 >= buf.length) return null;
        if (buf[i] === 0xff) {
          const n = buf[i + 1];
          if (n === 0x00 || (n >= 0xd0 && n <= 0xd7)) {
            i += 2;
            continue;
          }
          if (n !== 0xff) break;
        }
        i++;
      }
      out.push(buf.subarray(start, i));
      scan = false;
      continue;
    }
    if (buf[i] !== 0xff || i + 1 >= buf.length) return null;
    const marker = buf[i + 1];
    if (marker === 0xff) {
      i++;
      continue;
    }
    if (marker === EOI) {
      if (!frame) return null;
      out.push(buf.subarray(i, i + 2));
      return Buffer.concat(out);
    }
    // Marcadores sem tamanho fora dos dados comprimidos (ou um segundo início): não é JPEG normal.
    if (marker === 0xd8 || marker === 0x00 || marker === 0x01 || (marker >= 0xd0 && marker <= 0xd7)) return null;
    if (i + 4 > buf.length) return null;
    const len = buf.readUInt16BE(i + 2);
    if (len < 2 || i + 2 + len > buf.length) return null;
    if (SOF.has(marker)) {
      if (len < 8) return null;
      const height = buf.readUInt16BE(i + 5);
      const width = buf.readUInt16BE(i + 7);
      if (!height || !width || height > maxSide || width > maxSide) return null;
      frame = true;
    }
    if (marker === SOS) {
      if (!frame) return null;
      scan = true;
    }
    const meta = marker === 0xfe || (marker >= 0xe0 && marker <= 0xef);
    if (!meta || keepMeta(buf, i, marker)) out.push(buf.subarray(i, i + 2 + len));
    i += 2 + len;
  }
  return null;
}
