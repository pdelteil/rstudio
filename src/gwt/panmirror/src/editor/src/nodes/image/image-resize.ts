/*
 * image-resize.ts
 *
 * Copyright (C) 2019-20 by RStudio, PBC
 *
 * Unless you have received this program directly from RStudio pursuant
 * to the terms of a commercial license agreement with RStudio, then
 * this program is licensed to you under the terms of version 3 of the
 * GNU Affero General Public License. This program is distributed WITHOUT
 * ANY EXPRESS OR IMPLIED WARRANTY, INCLUDING THOSE OF NON-INFRINGEMENT,
 * MERCHANTABILITY OR FITNESS FOR A PARTICULAR PURPOSE. Please refer to the
 * AGPL (http://www.gnu.org/licenses/agpl-3.0.txt) for more details.
 *
 */


 // unit conversion logic
 // lockdown for percent
 // container for percent
 // figure is inline-block so percent based sizing doens't work well

 // selection not shown when double clicking number inputs
 // selection in textbox leads to "draggable"

import { EditorView } from 'prosemirror-view';
import { NodeWithPos } from 'prosemirror-utils';
import { NodeSelection } from 'prosemirror-state';

import { 
  createPopup, 
  createHorizontalPanel, 
  addHorizontalPanelCell, 
  createInputLabel, 
  createNumericInput, 
  createImageButton, 
  createCheckboxInput, 
  createSelectInput
} from '../../api/widgets';
import { EditorUI } from '../../api/ui';
import { editingRootNode } from '../../api/node';

import { imageDialog } from './image-dialog';
import { sizePropWithUnit, imageDimsWithUnits, pixelsToUnit, roundUnit } from './image-util';

// https://github.com/jgm/pandoc/blob/master/src/Text/Pandoc/ImageSize.hs
const kValidUnits = ["px", "in", "cm", "mm", "%"];// 

export function initResizeContainer(container: HTMLElement) {

  // add standard parent class
  container.classList.add('pm-image-resize-container', 'pm-selected-node-outline-color');

  // so that we are the offsetParent for the resize handles and shelf
  container.style.position = 'relative';

  // so that the container matches the size of the contained image
  container.style.display = 'inline-block';

  // so that the handles and shelf can be visible outside the boundaries of the image
  container.style.overflow = 'visible';

  // return for convenience
  return container;
}

export interface ResizeUI {
  update: () => void;
  detach: () => void;
}


export function isResizeUICompatible(img: HTMLImageElement) {

  const isCompatibleSize = (size: string | null) => {

    const sizeWithUnits = sizePropWithUnit(size);
    if (sizeWithUnits) {
      if (sizeWithUnits.unit) {
        
        return kValidUnits.includes(sizeWithUnits.unit);
      
      // no units is compatible (we'll just use pixels)
      } else {
        return true;
      }
    
    // no size at all is compatible (we'll just use pixels)
    } else {
      return true;
    }
  };

  return isCompatibleSize(img.style.width) && 
         isCompatibleSize(img.style.height);
}

export function attachResizeUI(
  imageNode: () => NodeWithPos,
  container: HTMLElement,
  img: HTMLImageElement,
  view: EditorView,
  ui: EditorUI 
) : ResizeUI {

  // indicate that resize ui is active
  container.classList.add('pm-image-resize-active');

  // handle both dims changed (from resize handle)
  const syncFromShelf = () => {
    updateImageSize(
      view, 
      imageNode(), 
      shelf.props.width() + shelf.props.units(), 
      shelf.props.height() + shelf.props.units()
    );
  };

  // handle width changed from shelf
  const onWidthChanged = () => {
    const width = shelf.props.width();
    if (width) {
      const height = shelf.props.lockRatio() ?
        (img.offsetHeight / img.offsetWidth) * width : 
        shelf.props.height();
      if (height) {
        const unit = shelf.props.units();
        shelf.props.setHeight(roundUnit(height, unit));
        syncFromShelf();
      }
    }
  };

  // handle height changed from shelf
  const onHeightChanged = () => {
    const height = shelf.props.height();
    if (height) {
      const width = shelf.props.lockRatio() ? 
        (img.offsetWidth / img.offsetHeight) * height :
        shelf.props.width();
      if (width) {
        const unit = shelf.props.units();
        shelf.props.setWidth(roundUnit(width, unit));
        syncFromShelf();
      }
    }
  };

  const onUnitsChanged = () => {

    // TODO: unit conversion 

    syncFromShelf();
  };

  // handle editImage request from shelf
  const onEditImage = () => {
    const nodeWithPos = imageNode();
    imageDialog(nodeWithPos.node, nodeWithPos.node.type, view.state, view.dispatch, view, ui, true);
  };

  // create resize shelf
  const shelf = resizeShelf(
    view, 
    img,
    () => {
      shelf.sync();
    },
    onWidthChanged,
    onHeightChanged,
    onUnitsChanged,
    onEditImage, 
    ui.context.translateText
  );
  container.append(shelf.el);
 
  // create resize handle and add it to the container
  const handle = resizeHandle(
    img, 
    shelf.props.lockRatio,
    shelf.sync,
    syncFromShelf
  );
  container.append(handle);

  
  // return functions that can be used to update and detach the ui
  return {
    update: () => {
      shelf.sync();
    },
    detach: () => {
      container.classList.remove('pm-image-resize-active');
      handle.remove();
      shelf.el.remove();
    }
  };
}

function resizeShelf(
  view: EditorView, 
  img: HTMLImageElement,
  onInit: () => void,
  onWidthChanged: () => void, 
  onHeightChanged: () => void,
  onUnitsChanged: () => void,
  onEditImage: () => void, 
  translateText: (text: string) => string
)  {

  // create resize shelf
  const shelf = createPopup(view, ['pm-text-color']);
 
  // update shelf position to make sure it's visible
  const updatePosition = () => {
    const kShelfRequiredSize = 340;
    const editingNode = editingRootNode(view.state.selection);
    const editingEl = view.domAtPos(editingNode!.pos + 1).node as HTMLElement;
    const editingBox = editingEl.getBoundingClientRect();
    const imageBox = img.getBoundingClientRect();
    const positionLeft = (imageBox.left + kShelfRequiredSize) < editingBox.right;
    if (positionLeft) {
      shelf.style.left = '0';
      if (img.offsetWidth < kShelfRequiredSize) {
        shelf.style.right = (img.offsetWidth - kShelfRequiredSize) + 'px';
      } else {
        shelf.style.right = '';
      }
    }
    else {
      shelf.style.right = '0';
      if (img.offsetWidth < kShelfRequiredSize) {
        shelf.style.left = (img.offsetWidth - kShelfRequiredSize) + 'px';
      } else {
        shelf.style.left = '';
      }
    }
  };

  // always position below
  shelf.style.bottom = "-48px";

  // helper function to get a dimension (returns null if input not currently valid)
  const getDim = (input: HTMLInputElement) => {
    const value = input.valueAsNumber;
    if (isNaN(value)) {
      return null;
    }
    if (value > 0) {
      return value;
    } else {
      return null;
    }
  };

  // main panel that holds the controls
  const panel = createHorizontalPanel();
  shelf.append(panel);
  const addToPanel = (widget: HTMLElement, paddingRight: number) => {
    addHorizontalPanelCell(panel, widget);
    const paddingSpan = window.document.createElement('span');
    paddingSpan.style.width = paddingRight + 'px';
    addHorizontalPanelCell(panel, paddingSpan);
  };
  
  const inputClasses = ['pm-text-color', 'pm-background-color'];

  // width
  const wLabel = createInputLabel('w:');
  addToPanel(wLabel, 4);
  const wInput = createNumericInput(4, 1, 10000, inputClasses);
  wInput.onchange = onWidthChanged;
  addToPanel(wInput, 8);

  // height
  const hLabel = createInputLabel('h:');
  addToPanel(hLabel, 4);
  const hInput = createNumericInput(4, 1, 10000, inputClasses);
  hInput.onchange = onHeightChanged;
  addToPanel(hInput, 10);

  // units
  const unitsSelect = createSelectInput(kValidUnits, inputClasses);
  unitsSelect.onchange = onUnitsChanged;
  addToPanel(unitsSelect, 12);

  // lock ratio
  const checkboxWrapper = window.document.createElement('div');
  const lockCheckbox = createCheckboxInput();
  lockCheckbox.checked = true;
  checkboxWrapper.append(lockCheckbox);
  addToPanel(checkboxWrapper, 4);
  const lockLabel = createInputLabel(translateText('Lock ratio'));
  addToPanel(lockLabel, 20);

  // edit button
  const editImage = createImageButton(
    ['pm-image-button-edit-properties'], 
    translateText('Edit Image')
  );
  editImage.onclick = onEditImage;
  addHorizontalPanelCell(panel, editImage);

  // run onInit
  if (img.complete) {
    setTimeout(onInit, 0);
  } else {
    img.onload = onInit;
  }

  return {
    el: shelf,

    // sync the shelf props to the current size/units of the image
    // we don't sync to the node b/c we want to benefit from automatic
    // unit handling in the conversion to the DOM
    sync: () => {

      // set ui based on current width and height style attributes
      const dims = imageDimsWithUnits(img);
      wInput.value = dims.width.size.toString();
      hInput.value = dims.height.size.toString();
      unitsSelect.value = dims.width.unit;
      
      // ensure we are positioned correctly (not offscreen, wide enough, etc.)
      updatePosition();
    },

    props: {
      width: () => getDim(wInput),
      setWidth: (width: string) => wInput.value = width,
      height: () => getDim(hInput),
      setHeight: (height: string) => hInput.value = height,
      units: () => unitsSelect.value,
      setUnits: (units: string) => unitsSelect.value = units,
      lockRatio: () => lockCheckbox.checked
    }
  };
}


function resizeHandle(
  img: HTMLImageElement, 
  lockRatio: () => boolean,
  onSizing: () => void,
  onSizingComplete: () => void
) {

  const handle = document.createElement('span');
  handle.classList.add(
    'pm-image-resize-handle', 
    'pm-background-color', 
    'pm-selected-node-border-color'
  );
  handle.style.position = 'absolute';
  handle.style.bottom = '-6px';
  handle.style.right = '-6px';
  handle.style.cursor = 'nwse-resize';

  const havePointerEvents = !!document.body.setPointerCapture;  

  const onPointerDown = (ev: MouseEvent) => {
    ev.preventDefault();

    const startWidth = img.offsetWidth;
    const startHeight = img.offsetHeight;

    const startX = ev.pageX;
    const startY = ev.pageY;

    const onPointerMove = (e: MouseEvent) => {

      // detect pointer movement
      const movedX = e.pageX - startX;
      const movedY = e.pageY - startY;

      let width;
      let height;
      if (lockRatio()) {
        if (movedX >= movedY) {
          width = startWidth + movedX; 
          height = startHeight + (movedX * (startHeight/startWidth));
        } else {
          height = startHeight + movedY;
          width = startWidth + (movedY * (startWidth/startHeight));
        }
      } else {
        width = startWidth + movedX;
        height = startHeight + movedY;
      }    
      
      // set image width and height based on units currnetly in use
      const dims = imageDimsWithUnits(img);
      img.style.width = pixelsToUnit(width, dims.width.unit, 0);
      img.style.height = pixelsToUnit(height, dims.height.unit, 0);

      onSizing();
    };

    const onPointerUp = (e: MouseEvent) => {
      e.preventDefault();

      // stop listening to events
      if (havePointerEvents) {
        handle.releasePointerCapture((e as PointerEvent).pointerId);
        handle.removeEventListener('pointermove', onPointerMove);
        handle.removeEventListener('pointerup', onPointerUp);
      } else {
        document.removeEventListener('mousemove', onPointerMove);
        document.removeEventListener('mouseup', onPointerUp);
      }
      
      // update image size
      onSizingComplete();
    };

    if (havePointerEvents) {
      handle.setPointerCapture((ev as PointerEvent).pointerId);
      handle.addEventListener('pointermove', onPointerMove);
      handle.addEventListener('pointerup', onPointerUp);
    } else {
      document.addEventListener('mousemove', onPointerMove);
      document.addEventListener('mouseup', onPointerUp);
    }
    
  };

  if (havePointerEvents) {
    handle.addEventListener('pointerdown', onPointerDown);
  } else {
    handle.addEventListener('mousedown', onPointerDown);
  }

  return handle;

}


function updateImageSize(view: EditorView, image: NodeWithPos, width: string, height: string) {

  // edit width & height in keyvalue
  let keyvalue = image.node.attrs.keyvalue as Array<[string, string]>;
  keyvalue = keyvalue.filter(value => !['width', 'height'].includes(value[0]));
  keyvalue.push(['width', width]);
  keyvalue.push(['height', height]);

  // create transaction
  const tr = view.state.tr;

  // set new attributes
  tr.setNodeMarkup(image.pos, image.node.type, { ...image.node.attrs, keyvalue });

  // restore node selection if our tr.setNodeMarkup blew away the selection
  const prevState = view.state;
  if (prevState.selection instanceof NodeSelection && prevState.selection.from === image.pos) {
    tr.setSelection(NodeSelection.create(tr.doc, image.pos));
  }

  // dispatch transaction
  view.dispatch(tr);
}
